/*
 * --------- BEGIN COPYRIGHT NOTICE ---------
 * Copyright 2002-2012 Extentech Inc.
 * Copyright 2013 Infoteria America Corp.
 * 
 * This file is part of OpenXLS.
 * 
 * OpenXLS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * OpenXLS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with OpenXLS.  If not, see
 * <http://www.gnu.org/licenses/>.
 * ---------- END COPYRIGHT NOTICE ----------
 */
package org.openxls.formats.XLS;

import org.openxls.ExtenXLS.ExcelTools;
import org.openxls.ExtenXLS.WorkBookHandle;
import org.openxls.formats.XLS.formulas.CalculationException;
import org.openxls.formats.XLS.formulas.CircularReferenceException;
import org.openxls.formats.XLS.formulas.FormulaCalculator;
import org.openxls.formats.XLS.formulas.FormulaParser;
import org.openxls.formats.XLS.formulas.GenericPtg;
import org.openxls.formats.XLS.formulas.Ptg;
import org.openxls.formats.XLS.formulas.PtgArea;
import org.openxls.formats.XLS.formulas.PtgArray;
import org.openxls.formats.XLS.formulas.PtgExp;
import org.openxls.formats.XLS.formulas.PtgMemArea;
import org.openxls.formats.XLS.formulas.PtgRef;
import org.openxls.toolkit.ByteTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

/**
 * FORMULA (0x406) describes a cell that contains a formula.
 * <pre>
 * offset  name        size    contents
 * 0       rw          2       Row
 * 2       col         2       Column
 * 4       ixfe        2       Index to the XF record
 * 6       num         8       Current value of the formula
 * 14      grbit       2       Option Flags
 * 16      chn         4       (Reserved, must be zero) A field that specifies an application-specific cache of information. This cache exists for performance reasons only, and can be rebuilt based on information stored elsewhere in the file without affecting calculation results.
 * 20      cce         2       Parsed Expression length
 * 22      rgce        cce     Parsed Expression
 * </pre>
 * The grbit field contains the following flags:
 * <pre>
 * byte   bits   mask   name           asserted if
 * 0      0      0x01   fAlwaysCalc    the result must not be cached
 *        1      0x02   fCalcOnLoad    the cached value is incorrect
 *        2      0x04   (Reserved)
 *        3      0x08   fShrFmla       this is a reference to a shared formula
 *        7-4    0xF0   (Unused)
 * 1      7-0    0xFF   (Unused)
 * </pre>
 * In most cases, formulas should have fAlwaysCalc asserted to ensure that
 * correct values are displayed upon opening of the file in Excel.
 */
public final class Formula extends XLSCellRecord
{
	/**
	 * The target ShrFmla record, if this is a shared formula reference.
	 */
	public Shrfmla shared = null;
	private static final Logger log = LoggerFactory.getLogger( Formula.class );
	private static final long serialVersionUID = 7563301825566021680L;
	/**
	 * Mask for the fAlwaysCalc grbit flag.
	 */
	private static final short FALWAYSCALC = 0x01;
	/**
	 * Mask for the fCalcOnLoad grbit flag.
	 */
	private static final short FCALCONLOAD = 0x02;
	/**
	 * Contains bitfield flags.
	 */
	private short grbit = FCALCONLOAD;
	/**
	 * Mask for the fShrFmla grbit flag.
	 */
	private static final short FSHRFMLA = 0x08;
	private static ThreadLocal<Integer> recurseCount = new ThreadLocal<Integer>()
	{
		@Override
		protected Integer initialValue()
		{
			return 0;
		}
	};
	private Object cachedValue;
	private Stack expression;
	/**
	 * Whether the record data needs to be updated.
	 */
	private boolean dirty = false;
	/**
	 * Whether this formula contains an indirect reference.
	 */
	private boolean containsIndirectFunction = false;
	/**
	 * Whether this FORMULA record has an attached STRING record.
	 */
	private boolean haveStringRec = false;
	/**
	 * The attached STRING record, if one exists.
	 */
	private StringRec string = null;
	/**
	 * List of records attached to this one.
	 */
	private List internalRecords;
	/**
	 * true if it's sub-ptgs are defined in other workbooks and therefore unable to be resolved
	 */
	private boolean isExternalRef = false;
	/**
	 * clear out object references in prep for closing workbook
	 */
	private boolean closed = false;

	/**
	 * Default constructor
	 */
	public Formula()
	{
		setOpcode( FORMULA );
		setIsValueForCell( true );
		isFormula = true;
	}

	public static String getTypeName()
	{
		return "formula";
	}

	/**
	 * Returns the correct string representation of a double for excel.
	 * <p/>
	 * Note this is for the standards that were determined with excel and extenxls
	 *
	 * @param num
	 * @return
	 */
	private static String getDoubleAsFormattedString( double theNum )
	{
		return ExcelTools.getNumberAsString( theNum );
	}

	/**
	 * increment each PtgRef in expression stack via row or column based
	 * on rowInc or colInc values
	 * Used in OOXML parsing
	 */
	public static void incrementSharedFormula( java.util.Stack origStack, int rowInc, int colInc, int[] range )
	{
		// traverse thru ptg's, incrementing row reference
		//java.util.Stack origStack= form.getExpression();	Don't do "in place" as alters original expression
		//Logger.logInfo("Before Inc: " + this.getFormulaString());
		for( int i = 0; i < origStack.size(); i++ )
		{
			Ptg p = (Ptg) origStack.elementAt( i );
			try
			{
				String s = p.getLocation();
				if( p.getIsReference() )
				{
					if( !((((PtgRef) p).wholeRow && (colInc != 0)) || (((PtgRef) p).wholeCol && (rowInc != 0))) )
					{
						if( !(p instanceof PtgArea) )
						{
							boolean[] bRelRefs = { ((PtgRef) p).isRowRel(), ((PtgRef) p).isColRel() };
							int[] rc = ExcelTools.getRowColFromString( s );
							if( bRelRefs[0] )
							{
								rc[0] += rowInc;
							}
							if( bRelRefs[1] )
							{
								rc[1] += colInc;
							}
							PtgRef pr = new PtgRef();
							pr.setParentRec( p.getParentRec() );
							pr.setUseReferenceTracker( false );    // 20090827 KSC: don't petform expensive calcs on init + removereferenceTracker blows out cached value
							pr.setLocation( ExcelTools.formatLocation( rc, bRelRefs[0], bRelRefs[1] ) );
							pr.setUseReferenceTracker( true );
							origStack.set( i, pr );
						}
						else
						{
							String sh = ExcelTools.stripSheetNameFromRange( s )[0];
							int[] rc = ExcelTools.getRangeRowCol( s );
							boolean[] bRelRefs = {
									((PtgArea) p).getFirstPtg().isRowRel(),
									((PtgArea) p).getLastPtg().isRowRel(),
									((PtgArea) p).getFirstPtg().isColRel(),
									((PtgArea) p).getLastPtg().isColRel()
							};
							if( bRelRefs[0] )
							{
								rc[0] += rowInc;
							}
							if( bRelRefs[1] )
							{
								rc[2] += rowInc;
							}
							if( bRelRefs[2] )
							{
								rc[1] += colInc;
							}
							if( bRelRefs[3] )
							{
								rc[3] += colInc;
							}
							PtgArea pa = new PtgArea( false );
							pa.setParentRec( p.getParentRec() );
							if( sh != null )
							{
								pa.setLocation( sh + "!" + ExcelTools.formatRangeRowCol( rc, bRelRefs ) );
							}
							else
							{
								pa.setLocation( ExcelTools.formatRangeRowCol( rc, bRelRefs ) );
							}
							pa.setUseReferenceTracker( true );
							origStack.set( i, pa );
						}
					}
				}
			}
			catch( Exception ex )
			{
				log.error( "Formula.incrementSharedFormula: " + ex.toString() );
			}
		}
	}

	/**
	 * returns true if the value s is one of the Excel defined Error Strings
	 *
	 * @param s
	 * @return
	 */
	public static boolean isErrorValue( String s )
	{
		if( s == null )
		{
			return false;
		}
		return (Collections.binarySearch( Arrays.asList( "#DIV/0!", "#N/A", "#NAME?", "#NULL!", "#NUM!", "#REF!", "#VALUE!" ), s.trim() ) > -1);
	}

	/**
	 * get the expression stack
	 *
	 * @return
	 */
	public Stack getExpression()
	{
		populateExpression();
		return expression;
	}

	/**
	 * for creating a formula on the fly.  This is used currently by FormulaParser
	 * to create a formula.  The location and XF fields are filled in by boundsheet.add();
	 */
	public void setExpression( Stack exp )
	{
		if( expression != null )
		{
			clearExpression();
		}
		expression = exp;
		updateRecord();
	}

	/**
	 * store formula-associated records: one or more of:
	 * ShrFmla
	 * StringRec
	 * Array
	 *
	 * @param b
	 */
	public void addInternalRecord( BiffRec b )
	{
		if( internalRecords == null )
		{
			internalRecords = new ArrayList( 3 );
		}

		if( b instanceof Shrfmla )
		{
			internalRecords.add( 0, b );
		}
		else if( b instanceof StringRec )
		{
			if( !haveStringRec || (string == null) )
			{// should ONLY have 1 StringRec
				internalRecords.add( b );
				haveStringRec = true;
				string = (StringRec) b;
				cachedValue = string.getStringVal();
			}
		}
		else    // array formula
		{
			internalRecords.add( b );
		}
	}

	public void removeInternalRecord( BiffRec b )
	{
		if( internalRecords == null )
		{
			return;
		}
		internalRecords.remove( b );
	}

	public List getInternalRecords()
	{
		if( internalRecords == null )
		{
			return Collections.emptyList();
		}
		return internalRecords;
	}

	public boolean hasAttachedString()
	{
		return haveStringRec;
	}

	/**
	 * Get the String that is attached to this formula (it has a result of a string..);
	 */
	public StringRec getAttatchedString()
	{
		return string;
	}

	/**
	 * return the "Calculate Always" setting for this formula
	 * used for formulas that always need calculating such as TODAY
	 *
	 * @return
	 */
	public boolean getCalcAlways()
	{
		return (grbit & FALWAYSCALC) != 0;
	}

	/**
	 * set the "Calculate Always setting for this formula
	 * used for formulas that always need calculating such as TODAY
	 *
	 * @param fAlwaysCalc
	 */
	public void setCalcAlways( boolean fAlwaysCalc )
	{
		if( fAlwaysCalc )
		{
			grbit |= FALWAYSCALC;
		}
		else
		{
			grbit &= ~FALWAYSCALC;
		}
	}

	/**
	 * set if this formula refers to External References
	 * (references defined in other workbooks)
	 *
	 * @param isExternalRef
	 */
	public void setIsExternalRef( boolean isExternalRef )
	{
		this.isExternalRef = isExternalRef;
	}

	/**
	 * Converts a shared formula reference into a normal formula.
	 *
	 * @throws IllegalStateException if this is not a shared formula member
	 */
	public void convertSharedFormula()
	{
		if( !isSharedFormula() )
		{
			throw new IllegalStateException( "not a shared formula reference" );
		}

		shared = null;
		setSharedFormula( false );
	}

	/**
	 * Returns the Human-Readable String Representation of
	 * this Formula.
	 */
	public String getFormulaString()
	{
		populateExpression();
		if( !isArrayFormula() )
		{
			return FormulaParser.getFormulaString( this );
		}
		return "{" + FormulaParser.getFormulaString( this ) + "}";

	}

	/**
	 * Returns the ptg that matches the string location sent to it.
	 * this can either be in the format "C5" or a range, such as "C4:D9"
	 */
	public List getPtgsByLocation( String loc ) throws FormulaNotFoundException
	{
		populateExpression();
		if( loc.indexOf( "!" ) == -1 )
		{
			loc = getSheet().getSheetName() + "!" + loc;
		}

		return ExpressionParser.getPtgsByLocation( loc, expression );
	}

	/**
	 * Returns an array of ptgs that represent any BiffRec ranges in the formula.
	 * Ranges can either be in the format "C5" or "Sheet1!C4:D9"
	 */
	public Ptg[] getCellRangePtgs() throws FormulaNotFoundException
	{
		return ExpressionParser.getCellRangePtgs( expression );
	}

	/**
	 * locks the Ptg at the specified location
	 */

	public boolean setLocationPolicy( String loc, int l )
	{
		populateExpression();
		try
		{
			List dx = getPtgsByLocation( loc );
			Iterator lx = dx.iterator();
			while( lx.hasNext() )
			{
				Ptg d = (Ptg) lx.next();
				d.setLocationPolicy( l );
				if( l == Ptg.PTG_LOCATION_POLICY_TRACK ) // init the tracker cell right away
				{
					d.initTrackerCell();
				}
			}
			return true;
		}
		catch( FormulaNotFoundException e )
		{
			log.warn( "locking Formula Location failed:" + loc + ": " + e.toString() );
			return false;
		}
	}

	/**
	 * Called to indicate that the parsed expression has changed.
	 * Does nothing if the expression doesn't exist yet.
	 */
	public void updateRecord()
	{
		dirty = true;
		if( data == null )
		{
			setData( new byte[6] );    // happens when newly init'ing a formula
		}

		if( (cachedValue instanceof String) && !"".equals( cachedValue ) && !isErrorValue( (String) cachedValue ) )
		{// if it's a string and not an error string
			if( !haveStringRec || (string == null) )
			{
//					this.addInternalRecord( new StringRec( (String)cachedValue ) ); // will be added in workbook.addRecord
				string = new StringRec( (String) cachedValue );
				string.setSheet( getSheet() );
				string.setRowNumber( getRowNumber() );
				string.setCol( getColNumber() );
				getWorkBook().setLastFormula( this );    // for addRecord, sets appropriate formula internal record
				getWorkBook().addRecord( string, true );
				haveStringRec = true;
			}
			else
			{
				string.setStringVal( (String) cachedValue );
			}
		}
		else if( string != null )
		{
			string.remove( false );
			removeInternalRecord( string );
			haveStringRec = false;
		}
	}

	@Override
	public Object clone()
	{
		// Make the record bytes available to XLSRecord.clone
		preStream();
		return super.clone();
	}

	public String toString()
	{
		populateExpression();
		return super.toString() + " : " + getFormulaString();
		//return this.worksheet.getSheetName() + "!" + this.getCellAddress() + ":" + this.getStringVal();
	}

	@Override
	public void close()
	{
		if( expression != null )
		{
			while( !expression.isEmpty() )
			{
				GenericPtg p = (GenericPtg) expression.pop();
				if( p instanceof PtgRef )
				{
					p.close();
				}
/*	        	else if (p instanceof PtgExp ) {
	        		Ptg[] ptgs= ((PtgExp)p).getConvertedExpression();
	        		for (int i= 0; i < ptgs.length; i++) {
	    	        	if (ptgs[i] instanceof PtgRef)
	    	        		((PtgRef) ptgs[i]).close();
	    	        	else
	    	        		((GenericPtg)ptgs[i]).close();
	        		}
	        	} */
				else
				{
					p.close();
				}
				p = null;
			}
		}
		if( string != null )
		{
			string.close();
			string = null;
		}
		if( shared != null )
		{
			if( (shared.getMembers() == null) || (shared.getMembers().size() == 1) )    // last one
			{
				shared.close();
			}
			else
			{
				shared.removeMember( this );
			}
			shared = null;
		}
		if( internalRecords != null )
		{
			internalRecords.clear();
		}
		super.close();
		closed = true;
	}

	/**
	 * Parses the record bytes.
	 * This method only needs to be called when the record is being constructed
	 * from bytes. Calling it on a programmatically created formula is
	 * unnecessary and will probably throw an exception.
	 */
	@Override
	public void init()
	{
		// Prevent misuse of init
		if( expression != null )
		{
			throw new IllegalStateException( "can't init a formula created from a string" );
		}

		super.init();
		if( (data = getData()) == null )
		{
			throw new IllegalStateException( "can't init a formula without record bytes" );
		}
		super.initRowCol();
		ixfe = ByteTools.readShort( getByteAt( 4 ), getByteAt( 5 ) );
		grbit = ByteTools.readShort( getByteAt( 14 ), getByteAt( 15 ) );

		// get the cached value bytes from the record
		byte[] currVal = getBytesAt( 6, 8 );

		// Is this a non-numeric value?
		if( (currVal[6] == (byte) 0xFF) && (currVal[7] == (byte) 0xFF) )
		{
			// String value
			if( currVal[0] == (byte) 0x00 )
			{
				haveStringRec = true;        // bytes 1-5 are not used
				// Normally cachedValue will be set by StringRec's init.
				// Setting cachedValue null forces calculation in the rare
				// event that the STRING record is missing or fails to init.
				cachedValue = null;
			}

			// Empty string value
			else if( currVal[0] == (byte) 0x03 )
			{
				// There is no attached STRING record. Set cachedValue directly.
				cachedValue = "";
			}

			// Boolean value
			else if( currVal[0] == (byte) 0x01 )
			{
				cachedValue = currVal[2] != (byte) 0x00;
			}

			// Error value
			else if( currVal[0] == (byte) 0x02 )
			{
				cachedValue = new CalculationException( currVal[2] );
			}

			// Unknown value type
			else
			{
				cachedValue = null;
			}

		}
		else
		{
			// do not cache NaN stored bytes
			double dbv = ByteTools.eightBytetoLEDouble( currVal );
			if( !Double.isNaN( dbv ) )
			{
				cachedValue = dbv;
			}
		}

		if( getSheet() == null )
		{
			setSheet( wkbook.getLastbound() );
		}

		try
		{
			log.trace( "Formula " + getCellAddress() + getFormulaString() );
		}
		catch( Exception e )
		{
			log.warn( "Debug output of Formula failed: " + e );
		}

		// The expression needs to be parsed on input in order to add it to
		// the reference tracker
		//TODO: Add a no calculation / read only mode without ref tracking
		populateExpression();
		// Perform some special handling for formulas with indirect references
		if( containsIndirectFunction )
		{
			registerIndirectFunction();
		}
		dirty = false;
	}

	@Override
	public boolean getBooleanVal()
	{
		Object obx = calculateFormula();
		try
		{
			if( obx instanceof Boolean )
			{
				return (Boolean) obx;
			}
		}
		catch( Exception e )
		{
			log.error( "getBooleanVal failed for: " + toString(), e );
		}

		try
		{
			String s = String.valueOf( obx );
			if( s.equalsIgnoreCase( "true" ) || s.equals( "1" ) )
			{
				return true;
			}

		}
		catch( Exception e )
		{
			log.warn( "getBooleanVal() failed: " + e );
		}
		return false;
	}

	/**
	 * Get the value of the formula as an integer.
	 * If the formula exceeds integer boundaries, or is a float with
	 * a non-zero mantissa throw an exception
	 *
	 * @see XLSRecord#getIntVal()
	 */
	@Override
	public int getIntVal() throws RuntimeException
	{
		Object obx = calculateFormula();
		try
		{
			double tl = (Double) obx;
			if( tl > Integer.MAX_VALUE )
			{
				throw new NumberFormatException( "getIntVal: Formula value is larger than the maximum java signed int size" );
			}
			if( tl < Integer.MIN_VALUE )
			{
				throw new NumberFormatException( "getIntVal: Formula value is smaller than the minimum java signed int size" );
			}
			double db = (Double) obx;
			int ret = ((Double) obx).intValue();
			if( ((db - ret) > 0) )
			{
				log.warn( "Loss of precision converting " + tl + " to int." );
			}

			return ret;
			// not back-compat return Integer.valueOf(new Long((long) tl).intValue()).intValue();
			// throw new NumberFormatException("Loss of precision converting " + tl + " to int.");
		}
		catch( ClassCastException e )
		{
			;
		}

		long l = 0;
		String s = String.valueOf( obx );

		// return a zero for empties
		if( s.equals( "" ) )
		{
			s = "0";
		}
		try
		{
			String t = "";
			java.math.BigDecimal bd = new java.math.BigDecimal( s );
			l = bd.longValue();    // 20090514 KSC: bd.intValueExact(); is 1.6 compatible -- MAY CAUSE INFOTERIA REGRESSION ERROR
			if( l > Integer.MAX_VALUE )
			{
				throw new NumberFormatException( "Formula value is larger than the maximum java signed int size" );
			}
			if( l < Integer.MIN_VALUE )
			{
				throw new NumberFormatException( "Formula value is smaller than the minimum java signed int size" );
			}
			return Integer.valueOf( new Long( l ).toString() );
		}
		catch( NumberFormatException ne )
		{
			throw new NumberFormatException( "getIntVal: Formula is a non-numeric value" );
		}
		catch( Exception e )
		{
			throw new NumberFormatException( "getIntVal: " + e );
		}
	}

	@Override
	public double getDblVal()
	{
		Object obx = calculateFormula();
		try
		{
			if( obx instanceof Double )
			{
				double d = (Double) obx;
				return d;
			}
		}
		catch( Exception e )
		{
			log.error( "Formula.getDblVal failed for: " + toString(), e );
		}

		String s = String.valueOf( obx );

		// return a zero for empties
		if( s.equals( "" ) )
		{
			s = "0";
		}
		try
		{
			Double d = new Double( s );
			return d;
		}
		catch( NumberFormatException ex )
		{
			return Double.NaN;
		}
		catch( Exception e )
		{
			log.warn( "Formula.getDblVal() failed: " + e );
		}
		return Double.NaN;
	}

	@Override
	public float getFloatVal()
	{
		Object obx = calculateFormula();
		try
		{
			if( obx instanceof Float )
			{
				float d = (Float) obx;
				return d;
			}
		}
		catch( Exception e )
		{
			log.error( "Formula.getFloatVal failed for: " + toString(), e );
		}

		try
		{
			String s = String.valueOf( obx );

			// return a zero for empties
			if( s.equals( "" ) )
			{
				s = "0";
			}
			Float d = new Float( s );
			return d;
		}
		catch( NumberFormatException ex )
		{
			return Float.NaN;
		}
		catch( Exception e )
		{
			log.warn( "Formula.getFloatVal() failed: " + e );
		}
		return Float.NaN;
	}

	/**
	 * return the String representation of the current Formula value
	 */
	@Override
	public String getStringVal()
	{
		Object obx = calculateFormula();
		try
		{
			if( obx instanceof Double )
			{
				double d = (Double) obx;
				if( !Double.isNaN( d ) )
				{
					return Formula.getDoubleAsFormattedString( d );
				}
				return "NaN";
			}
		}
		catch( Exception e )
		{
			log.error( "Formula.getStringVal failed for: " + toString(), e );
		}
		// if null, return empty string
		if( obx == null )
		{
			return "";
		}
		return obx.toString();

	}

	@Override
	public void setStringVal( String v )
	{
		throw new CellTypeMismatchException( "Attempting to set a string value on a formula" );
		// TODO: set the string value of the attached string?
	}

	/**
	 * Updates the record data if necessary to prepare for streaming.
	 */
	@Override
	public void preStream()
	{
		// If the record doesn't need to be updated, do nothing
		if( !dirty && !isSharedFormula() && (cachedValue != null) &&
				(getWorkBook().getCalcMode() != CALCULATE_EXPLICIT) )
		{
			return;
		}

		// If the formula needs calculation, do so
		try
		{
			if( cachedValue == null )
			{
				calculateFormula();
			}
		}
		catch( FunctionNotSupportedException e )
		{
			// Fall through to null cachedValue handling below
		}

		// Sometimes we need to write a value other than the real cached value
		Object writeValue = cachedValue;

		// Handle formulas that can't be calculated for whatever reason
		if( cachedValue == null )
		{
			grbit |= FCALCONLOAD;
			if( writeValue == null )
			{
				writeValue = Double.NaN;
			}
		}

		// Handle CALCULATE_EXPLICIT mode
		if( getWorkBook().getCalcMode() == CALCULATE_EXPLICIT )
		{
			grbit |= FCALCONLOAD;
		}

		// If this is a shared formula, write a PtgExp
		Stack expr = expression;
		if( isSharedFormula() )
		{	/* ONLY need to do this if shared formula member(s) have changed-
										a better choice is to trap and change in respective method */
			expr = new Stack();
			expr.add( shared.getPointer() );
		}

		// Fetch the Ptg data and calculate the expression size
		byte[][] ptgdata = new byte[expr.size()][];
		byte[] rgb = null;
		short cce = 0;
		short rgblen = 0;
		for( int idx = 0; idx < expr.size(); idx++ )
		{
			Ptg ptg = (Ptg) expr.get( idx );
			ptgdata[idx] = ptg.getRecord();
			cce += ptgdata[idx].length;

			if( ptg instanceof PtgArray )
			{
				byte[] extra = ((PtgArray) ptg).getPostRecord();
				rgb = ByteTools.append( extra, rgb );
				rgblen += extra.length;
			}
			else if( ptg instanceof PtgMemArea )
			{    // has PtgExtraMem structure appended
				byte[] extra = ((PtgMemArea) ptg).getPostRecord();
				rgb = ByteTools.append( extra, rgb );
				rgblen += extra.length;
			}
		}

		byte[] newdata = new byte[22 + cce + rgblen];
		// Cell Header (row, col, ixfe)
		System.arraycopy( data, 0, newdata, 0, 6 );

		// Cached Value (num)
		byte[] value;
		if( writeValue instanceof Number )
		{
			//TODO: Check infinity, NaN, etc.
			value = ByteTools.toBEByteArray( ((Number) writeValue).doubleValue() );
		}
		else
		{
			value = new byte[8];
			// byte 0 specifies the marker type
			value[1] = (byte) 0x00;
			// byte 2 is used by bool and boolerr
			value[3] = (byte) 0x00;
			value[4] = (byte) 0x00;
			value[5] = (byte) 0x00;
			value[6] = (byte) 0xFF;
			value[7] = (byte) 0xFF;

			if( writeValue instanceof String )
			{
				if( !isErrorValue( (String) writeValue ) )
				{
					value[2] = (byte) 0x00;
					String sval = (String) writeValue;
					if( sval.equals( "" ) || (string == null) )
					{    // the latter can occur when input from XLSX; a cachedvalue is set without an associated StringRec
						value[0] = (byte) 0x03; // means empty
					}
					else
					{
						value[0] = (byte) 0x00;
						string.setStringVal( sval );
					}
				}
				else
				{
					value[0] = (byte) 0x02;    // error code
					value[2] = CalculationException.getErrorCode( (String) writeValue );
				}
			}
			else if( writeValue instanceof Boolean )
			{
				value[0] = (byte) 0x01;
				value[2] = (((Boolean) writeValue).booleanValue() ? (byte) 0x01 : (byte) 0x00);
			}
			else if( writeValue instanceof CalculationException )
			{
				value[0] = (byte) 0x02;
				value[2] = ((CalculationException) writeValue).getErrorCode();
			}
			else
			{
				throw new Error( "unknown value type " + ((writeValue == null) ? "null" : writeValue.getClass().getName()) );
			}
		}
		System.arraycopy( value, 0, newdata, 6, 8 );

		// Bit Flags (grbit)
		System.arraycopy( ByteTools.shortToLEBytes( grbit ), 0, newdata, 14, 2 );

		// chn - reserved zero
		Arrays.fill( newdata, 16, 19, (byte) 0x00 );

		// Expression Length (cce)
		System.arraycopy( ByteTools.shortToLEBytes( cce ), 0, newdata, 20, 2 );

		// Expression Ptgs (rgce)
		int offset = 22;
		for( int idx = 0; idx < ptgdata.length; idx++ )
		{
			System.arraycopy( ptgdata[idx], 0, newdata, offset, ptgdata[idx].length );
			offset += ptgdata[idx].length;
		}

		// Expression Extra Data (rgb)
		if( rgblen > 0 )
		{
			System.arraycopy( rgb, 0, newdata, offset, rgblen );
		}
		setData( newdata );
		dirty = false;
	}

	/**
	 * Calculates the formula honoring calculation mode.
	 *
	 * @throws CalculationException
	 */
	public Object calculateFormula() throws FunctionNotSupportedException
	{
		// if this is calc explicit, we ALWAYS use cache
		if( getWorkBook().getCalcMode() == CALCULATE_EXPLICIT )
		{
			return cachedValue;
		}
		// TODO: IF ALREADY RECALCED DONT SET TO null -- need flag?
		if( getWorkBook().getCalcMode() == CALCULATE_ALWAYS )
		{
			if( !isExternalRef )    // if it's an external reference DONT CLEAR CACHE
			{
				cachedValue = null; // force calc
			}
			else
			{
				return cachedValue;
			}
		}
		return calculate();

	}

	/**
	 * Calculate the formula if necessary.  This accessor resets the recurse count on the
	 * formula.  Excel standard is to allow 100 recursions before throwing a circular reference.
	 *
	 * @throws CalculationException
	 */
	public Object calculate()
	{
		Integer depth = recurseCount.get();

		try
		{
			log.debug( "Calculating Formula: {}", getFormulaString() );
			recurseCount.set( depth + 1 );
			if( depth > WorkBookHandle.RECURSION_LEVELS_ALLOWED )
			{
				log.warn( "Recursion levels reached in calculating formula " + getCellAddressWithSheet() + ". Possible circular reference.  Recursion levels can be set through WorkBookHandle.setFormulaRecursionLevels" );
				cachedValue = new CalculationException( CalculationException.CIR_ERR );
				return cachedValue;
			}
			return calculateInternal();
		}
		finally
		{
			recurseCount.set( depth );
		}
	}

	/**
	 * Returns whether this is a reference to a shared formula.
	 */
	public boolean isSharedFormula()
	{
		return (grbit & FSHRFMLA) != 0;
	}

	/**
	 * Sets whether this is a reference to a shared formula.
	 */
	private void setSharedFormula( boolean isSharedFormula )
	{
		if( isSharedFormula )
		{
			grbit |= FSHRFMLA;
		}
		else
		{
			grbit &= ~FSHRFMLA;
		}
	}

	/**
	 * return truth of "this is an array formula" i.e. contains an Array sub-record
	 *
	 * @return
	 */
	public boolean isArrayFormula()
	{
		if( (internalRecords != null) && (internalRecords.size() > 0) )
		{
			return (internalRecords.get( 0 ) instanceof Array);
		}
		return false;
	}

	/**
	 * fetches the internal Array record linked to this formula, if any,
	 * or null if not an array formula
	 *
	 * @return array record
	 * @see isArrayFormula
	 */
	public Array getArray()
	{
		try
		{
			return ((Array) internalRecords.get( 0 ));
		}
		catch( Exception e )
		{
			;
		}
/*		if (expression.get(0) instanceof PtgExp) {
			// if it's the child of a parent array formula, obtain it's Array record
			// TODO: verify this is correct + finish
		}*/
		return null;
	}

	/**
	 * Set the cached value of this formula,
	 * in cases where the formula is null, set the cache to null,
	 * as well as updating the attached string to null in order to force
	 * recalc
	 *
	 * @see XLSRecord#setCachedValue(java.lang.Object)
	 */
	public void setCachedValue( Object newValue )
	{
		if( newValue == null )
		{
			clearCachedValue();
		}
		else
		{
			cachedValue = newValue;    // TODO: need to check/validate StringRec ????
		}
	}

	/**
	 * Set the cached value of this formula,
	 * in cases where the formula is null, set the cache to null,
	 * as welll as updating the attached string to null in order to force
	 * recalc
	 *
	 * @see XLSRecord#setCachedValue(java.lang.Object)
	 */
	public void clearCachedValue()
	{
		cachedValue = null;
		haveStringRec = false;
//         this.updateRecord(); no need; will be updated after recalc, which will automatically happen on write
	}

	public String getArrayRefs()
	{
		if( (internalRecords != null) && (internalRecords.size() > 0) )
		{
			Object o = internalRecords.get( 0 );
			return ((Array) o).getArrayRefs();
		}
		return "";
	}

	/**
	 * OOXML-specific: set the range the Array references
	 *
	 * @param s
	 */
	public void setArrayRefs( String s )
	{
		if( (internalRecords != null) && (internalRecords.size() > 0) )
		{
			Object o = internalRecords.get( 0 );
			if( o instanceof Array )
			{
				Array a = (Array) o;
				int[] rc = ExcelTools.getRangeRowCol( s );
				a.setFirstRow( rc[0] );
				a.setFirstCol( rc[1] );
				a.setLastRow( rc[2] );
				a.setLastCol( rc[3] );
			}
		}
	}

	/**
	 * Performs cleanup needed before removing the formula cell from the
	 * work sheet. The formula will not behave correctly once this is called.
	 */
	public void destroy()
	{
		clearExpression();
	}

	/**
	 * Replaces a ptg in the active expression.  Useful for replacing a ptgRef with a ptgError after a bad movement.
	 *
	 * @param thisptg
	 * @param ptgErr
	 */
	public void replacePtg( Ptg thisptg, Ptg ptgErr )
	{
		ptgErr.setParentRec( this );
		int idx = expression.indexOf( thisptg );
		expression.remove( idx );
		expression.insertElementAt( ptgErr, idx );
	}

	/**
	 * Adds an indirect function to the list of functions to be evaluated post load
	 */
	protected void registerIndirectFunction()
	{
		getWorkBook().addIndirectFormula( this );
	}

	/**
	 * If the method contains an indirect function then
	 * register those ptgs into the reference tracker.
	 * <p/>
	 * In order to do this it is necessary to calculate the formula
	 * to retrieve the Ptg's
	 */
	protected void calculateIndirectFunction()
	{
		clearCachedValue();
		try
		{
			calculateFormula();
		}
		catch( FunctionNotSupportedException e )
		{
			// If we do not support the function, calculation will throw a FNE anyway, so no need for logging
		}
		catch( Exception e )
		{
			// problematic here.  As the calculation is happening on parse we dont really
			// want to throw an exception and crap out on the book loading
			// but the client should be informed in some way.  Also a generic exception is caught
			// because our code does not bubble a calc exception up.
			log.error( "Error registering lookup for INDIRECT() function at cell: " + getCellAddress() + " : " + e );
		}
	}

	/**
	 * Set if the formula contains Indirect()
	 *
	 * @param containsIndirectFunction The containsIndirectFunction to set.
	 */
	protected void setContainsIndirectFunction( boolean containsIndirectFunction )
	{
		this.containsIndirectFunction = containsIndirectFunction;
	}

	@Override
	protected void finalize()
	{
		if( !closed )
		{
			close();
		}
	}

	/**
	 * Populates the expression in the formula.  This has been moved out of init for performance reasons.
	 * The idea is that the processing is offloaded as a JIT for calculation/value retrieval.
	 */
	//TODO: refactor external references and make private
	void populateExpression()
	{
		if( (expression != null) || (data == null) )
		{
			return;
		}

		try
		{
			short length = ByteTools.readShort( getByteAt( 20 ), getByteAt( 21 ) );

			if( (length + 22) > data.length )
			{
				throw new Exception( "cce longer than record" );
			}

			expression = ExpressionParser.parseExpression( getBytesAt( 22, reclen - 22 ), this, length );

			// If this is a shared formula reference, do some special init
			if( isSharedFormula() )
			{
				initSharedFormula( null );
			}
		}
		catch( Exception e )
		{
			log.error( "Formula.init:  Parsing Formula failed: " + e );
		}
	}

	/**
	 * Performs special initialization for shared formula references.
	 *
	 * @param target the target <code>SHRFMLA</code> record. If this is
	 *               <code>null</code> it will be retrieved from the cell pointed to
	 *               by the <code>PtgExp</code>.
	 * @throws FormulaNotFoundException if the target shared formula is missing
	 * @throws IllegalArgumentException if this is not a shared formula member
	 */
	void initSharedFormula( Shrfmla target ) throws FormulaNotFoundException
	{
		if( !isSharedFormula() )
		{
			setSharedFormula( true );
		}

		// If we're already done, silently do nothing
		if( shared != null )
		{
			return;
		}

		// If this is an instantiation instead of a reference
		if( (expression.size() != 1) || !(expression.get( 0 ) instanceof PtgExp) )
		{
			//TODO: find which ShrFmla this is and convert to reference
			// For now, just clear fShrFmla
			setSharedFormula( false );
			return;
		}

		PtgExp pointer = (PtgExp) expression.get( 0 );

		if( target != null )
		{
			shared = target;
		}
		else
		{
			try
			{
				shared = ((Formula) getSheet().getCell( pointer.getRwFirst(),
				                                        pointer.getColFirst() )).shared;    // find shared cell linked to host/first formula cell in shared formula series
				if( shared == null )
				{
					throw new Exception();
				}
			}
			catch( Exception e )
			{
				// If this is the host cell, fail silently. This method will be
				// re-called by the ShrFmla record's init method.
				if( getCellAddress().equals( pointer.getReferent() ) )
				{
					return;
				}

				// Otherwise, complain and clear fShrFmla
				throw new FormulaNotFoundException( "FORMULA at " + getCellAddress() + " refers to missing SHRFMLA at " + pointer.getReferent() );
			}
		}

		//Shared Formula Init Performance Changes:  do not instantiate until calculate
//		expression = shared.instantiate( pointer );
		shared.addMember( this );

		if( shared.containsIndirectFunction )
		{
			registerIndirectFunction();
		}
	}

	/**
	 * Performs cleanup required before changing or removing this Formula.
	 * This nulls out the expression, so save a copy if you need it.
	 * Possible sub-records associated with Formula: array, shared string and/or shared formula
	 */
	private void clearExpression()
	{
		if( expression == null )
		{
			return;
		}

		if( isArrayFormula() )
		{
			Array a = getArray();
			if( a != null )
			{
				getSheet().removeRecFromVec( a );
			}
		}
		if( hasAttachedString() )
		{    // remove that too
			if( string != null )
			{
				getSheet().removeRecFromVec( string );
			}
			string = null;
		}

		if( isSharedFormula() )
		{
			shared.removeMember( this );
			shared = null;
			setSharedFormula( false );
		}

		Iterator iter = expression.iterator();
		while( iter.hasNext() )
		{
			Ptg ptg = (Ptg) iter.next();
			if( ptg instanceof PtgRef )
			{
				((PtgRef) ptg).removeFromRefTracker();
			}
		}

		expression = null;
	}

	/**
	 * Calculates the formula if necessary regardless of calculation mode.
	 * If there is a cached value it will be returned. Otherwise, the formula
	 * will be calculated and the result will be cached and returned. If you
	 * need to force calculation call {@link #clearCachedValue()} first.
	 */
	private Object calculateInternal()
	{
		// If we have a cached value, return it instead of calculating
		if( cachedValue != null )
		{
			log.trace( "Returning cached value..." );
			return cachedValue;
		}

		populateExpression();

		try
		{
			cachedValue = FormulaCalculator.calculateFormula( expression );
		}
		catch( StackOverflowError e )
		{
			log.warn( "Stack overflow while calculating " + getCellAddressWithSheet() + ". Possible circular reference." );
			cachedValue = new CalculationException( CalculationException.CIR_ERR );
			return cachedValue;
		}

		if( cachedValue == null )
		{
			throw new FunctionNotSupportedException( "Unable to calculate Formula " + getFormulaString() + " at: " + getSheet().getSheetName() + "!" + getCellAddress() );
		}

		if( cachedValue.toString().equals( "#CIR_ERR!" ) )
		{
			return new CircularReferenceException( CalculationException.CIR_ERR );
		}
		if( cachedValue.toString().length() < 1 )
		{
			// do something...?
		}
		else if( cachedValue.toString().charAt( 0 ) == '{' )
		{
			// it's an array, we need to find the particular value that we want.
			// parse all array strings into rows, cols
			String arrStr = (String) cachedValue;
			arrStr = arrStr.substring( 1, arrStr.length() - 1 );
			String[] rows = null;
			String[][] cols = null;
			// split rows
			rows = arrStr.split( ";" );
			cols = new String[rows.length][];
			for( int i = 0; i < rows.length; i++ )
			{
				cols[i] = rows[i].split( ",", -1 );    // include empty strings
			}
			PtgExp pxp;
			int rowA;
			int colA;
			Ptg p;
			try
			{
				pxp = (PtgExp) expression.elementAt( 0 );
				rowA = getRowNumber() - pxp.getRwFirst();
				colA = getColNumber() - pxp.getColFirst();
				// now, if it's a 1-dimensional array e.g {1,2,3,4,5}, nr=1, nc= 5
				// if formula address is traversing rows then switch
				if( (rows.length == 1) && (rowA > 0) && (colA == 0) )
				{
					colA = rowA;
					rowA = 0;
				}
			}
			catch( ClassCastException e )
			{
				// this is when we just calc'd a formula and have no exp reference.
				// assume it is the location of the formula
				// could be incorrect, may need to revisit
				rowA = 0;
				colA = 0;
			}
			cachedValue = cols[rowA][colA];
			// try to cast
			try
			{
				cachedValue = new Double( (String) cachedValue );
			}
			catch( Exception e )
			{
				; // let it go
			}
			log.debug( "Cached Value: {}", cachedValue );
		}
		if( getAttatchedString() != null )
		{
			getAttatchedString().setStringVal( String.valueOf( cachedValue ) );
		}

		updateRecord();
		return cachedValue;
	}

}