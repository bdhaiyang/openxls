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
import org.openxls.formats.XLS.formulas.FormulaCalculator;
import org.openxls.formats.XLS.formulas.FormulaParser;
import org.openxls.formats.XLS.formulas.Ptg;
import org.openxls.formats.XLS.formulas.PtgArray;
import org.openxls.formats.XLS.formulas.PtgExp;
import org.openxls.formats.XLS.formulas.PtgMemArea;
import org.openxls.formats.XLS.formulas.PtgRef;
import org.openxls.toolkit.ByteTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Stack;

/**
 * The Array class describes a formula that was Array-entered into a range of cells.
 * <p/>
 * The range of Cells in which the array is entered is defined by the rwFirst, last, colFirst and last fields.
 * <p/>
 * The Array record occurs directly after the Formula record for the cell in the upper-left corner of the array -- that is, the cell
 * defined by the rwFirst and colFirst fields.
 * <p/>
 * The parsed expression is the array formula -- consisting of Ptgs.
 * <p/>
 * You should ignore the chn field when you read the file, it must be 0 if written.
 * <p/>
 * <p/>
 * OFFSET		NAME			SIZE		CONTENTS
 * -----
 * 4				rwFirst			2			FirstRow of the array
 * 6				fwLast			2			Last Row of the array
 * 8				colFirst		1			First Column of the array
 * 9				colLast			1			Last Column of the array
 * 10			grbit			2			Option Flags
 * 12			chn				4			set to 0, ignore
 * 16			cce				2			Length of the parsed expression
 * 18			rgce			var		Parsed Expression
 * <p/>
 * grbit fields
 * bit 1 = fAlwaysCalc - always calc the formula
 * bit 2 = fCalcOnLoad - calc formula on load
 */
public final class Array extends XLSRecord
{
	private static final Logger log = LoggerFactory.getLogger( Array.class );
	private static final long serialVersionUID = -7316545663448065447L;
	private short rwFirst;
	private short rwLast;
	private short colFirst;
	private short colLast;
	private short cce;
	private short grbit;
	private byte[] rgce;
	private Formula parentRec = null;

	public int getFirstRow()
	{
		return rwFirst;
	}

	public int getLastRow()
	{
		return rwLast;
	}

	public int getFirstCol()
	{
		return colFirst;
	}

	public int getLastCol()
	{
		return colLast;
	}

	/*
	 * For getRow() and getCol() we are going to return the upper-right hand
	 * location of the sharedformula.  This should be the same location referenced
	 * in the PTGExp associated with these formulas as well
	 */
	@Override
	public int getRowNumber()
	{
		return getFirstRow();
	}

	public int getCol()
	{
		return getFirstCol();
	}

	public void setFirstRow( int i )
	{
		rwFirst = (short) i;
	}

	public void setLastRow( int i )
	{
		rwLast = (short) i;
	}

	public void setFirstCol( int i )
	{
		colFirst = (byte) i;
	}

	public void setLastCol( int i )
	{
		colLast = (byte) i;
	}

	private Stack expression;

	@Override
	public void init()
	{
		super.init();
		setOpcode( ARRAY );
// NO NOT TRUE!!!!!        super.setIsValueForCell(true); // this ensures that it gets picked up by the Formula...
// will get "picked up" by formula due to WorkBook.addRecord code
// isValueForCell will DELETE THE CELL if remove/change NOT WHAT WE WANT HERE
		rwFirst = (short) ByteTools.readInt( getByteAt( 0 ), getByteAt( 1 ), (byte) 0, (byte) 0 );
		rwLast = (short) ByteTools.readInt( getByteAt( 2 ), getByteAt( 3 ), (byte) 0, (byte) 0 );

		colFirst = (short) ByteTools.readUnsignedShort( getByteAt( 4 ), (byte) 0 );
		colLast = (short) ByteTools.readUnsignedShort( getByteAt( 5 ), (byte) 0 );
		grbit = ByteTools.readShort( getByteAt( 6 ), getByteAt( 7 ) );
		cce = ByteTools.readShort( getByteAt( 12 ), getByteAt( 13 ) );
		rgce = getBytesAt( 14, cce );

		expression = ExpressionParser.parseExpression( rgce, this );

		// for PtgArray and PtgMemAreas, have Constant Array Tokens following expression 
		// this length is NOT included in cce 		
		int posExtraData = cce + 14;
		int len = getData().length;
		for( Object anExpression : expression )
		{
			if( anExpression instanceof PtgArray )
			{
				try
				{
					byte[] b = new byte[(len - posExtraData)];
					System.arraycopy( getData(), posExtraData, b, 0, len - posExtraData );
					PtgArray pa = (PtgArray) anExpression;
					posExtraData += pa.setArrVals( b );
				}
				catch( Exception e )
				{
					log.warn( "Array: error getting Constants " + e.getLocalizedMessage() );
				}
			}
			else if( anExpression instanceof PtgMemArea )
			{
				try
				{
					PtgMemArea pm = (PtgMemArea) anExpression;
					byte[] b = new byte[pm.getnTokens() + 8];
					System.arraycopy( pm.getRecord(), 0, b, 0, 7 );
					System.arraycopy( getData(), posExtraData, b, 7, pm.getnTokens() );
					pm.init( b );
					posExtraData += pm.getnTokens();
				}
				catch( Exception e )
				{
					log.warn( "Array: error getting memarea constants " + e.toString() );
				}
			}
		}
			log.debug( "Array encountered at: " + wkbook.getLastbound()
			                                                      .getSheetName() + "!" + ExcelTools.getAlphaVal( colFirst ) + (rwFirst + 1) + ":" + ExcelTools
					.getAlphaVal( colLast ) + (rwLast + 1) );
	}

	/**
	 * Associate this record with a worksheet.
	 * init array refs as well
	 */
	@Override
	public void setSheet( Sheet b )
	{
		worksheet = b;
		// add to array formula references since this is the parent 
		if( expression != null )
		{ // it's been initted
			String loc = ExcelTools.formatLocation( new int[]{
					rwFirst, colFirst
			} );    // this formula address == array formula references for OOXML usage
			String ref = ExcelTools.formatRangeRowCol( new int[]{ rwFirst, colFirst, rwLast, colLast } );
			((Boundsheet) b).addParentArrayRef( loc, ref );        // formula address, array formula references OOXML usage
		}

	}

	/**
	 * init Array record from formula string
	 *
	 * @param fmla
	 */
	public void init( String fmla, int rw, int col )
	{
// NO NOT TRUE!!!!!        super.setIsValueForCell(true); // this ensures that it gets picked up by the Formula...
// will get "picked up" by formula due to WorkBook.addRecord code
// isValueForCell will DELETE THE CELL if remove/change NOT WHAT WE WANT HERE
		// TODO: ever a case of rwFirst!=rwLast, colFirst!=colLast ?????
		setOpcode( ARRAY );
		rwFirst = (short) rw;
		rwLast = (short) rw;
		colFirst = (byte) col;
		colLast = (byte) col;
		grbit = 0x2;    // calc on load = default	 20090824 KSC	[BugTracker 2683]
		fmla = fmla.substring( 1, fmla.length() - 1 );     // parse formula string and add stack to Array record
		Stack newptgs = FormulaParser.getPtgsFromFormulaString( this, fmla );
		expression = newptgs;
		updateRecord();

	}

	/*  Update the record byte array with the modified ptg records
	 */
	public void updateRecord()
	{
		// first, get expression length
		cce = 0;        // sum up expression (Ptg) records
		for( int i = 0; i < expression.size(); i++ )
		{
			Ptg ptg = (Ptg) expression.elementAt( i );
			cce += ptg.getRecord().length;
			if( ptg instanceof PtgRef )
			{
				((PtgRef) ptg).setArrayTypeRef();
			}
		}

		byte[] newdata = new byte[14 + cce];        // total record data (not including extra data, if any)

		int pos = 0;
		// 20090824 KSC: [BugTracker 2683] 
		// use setOpcode rather than setting 1st byte as it's a record not a ptg		newdata[0]= (byte) XLSConstants.ARRAY;			pos++;
		setOpcode( ARRAY );
		System.arraycopy( ByteTools.shortToLEBytes( rwFirst ), 0, newdata, pos, 2 );
		pos += 2;
		System.arraycopy( ByteTools.shortToLEBytes( rwLast ), 0, newdata, pos, 2 );
		pos += 2;
		newdata[pos++] = (byte) colFirst;
		newdata[pos++] = (byte) colLast;
		System.arraycopy( ByteTools.shortToLEBytes( grbit ), 0, newdata, pos, 2 );    // 20090824 KSC: Added [BugTracker 2683]

		pos = 12;
		System.arraycopy( ByteTools.shortToLEBytes( cce ), 0, newdata, pos, 2 );
		pos += 2;

		// expression
		rgce = new byte[cce];                    // expression record data
		pos = 0;
		byte[] arraybytes = new byte[0];
		for( int i = 0; i < expression.size(); i++ )
		{
			Ptg ptg = (Ptg) expression.elementAt( i );
			// trap extra data after expression (not included in cce count)
			if( ptg instanceof PtgArray )
			{
				PtgArray pa = (PtgArray) ptg;
				arraybytes = ByteTools.append( pa.getPostRecord(), arraybytes );
			}
			else if( ptg instanceof PtgMemArea )
			{
				PtgMemArea pm = (PtgMemArea) ptg;
				arraybytes = ByteTools.append( pm.getPostRecord(), arraybytes );
			}
			byte[] b = ptg.getRecord();
			System.arraycopy( b, 0, rgce, pos, ptg.getLength() );
			pos = pos + ptg.getLength();
		}
		System.arraycopy( rgce, 0, newdata, 14, cce );
		newdata = ByteTools.append( arraybytes, newdata );
		setData( newdata );
	}

	/**
	 * Returns the top left location of the array, used for identifying which array goes with what formula.
	 */
	public String getParentLocation()
	{
		int[] in = new int[2];
		in[0] = colFirst;
		in[1] = rwFirst;
		String s = ExcelTools.formatLocation( in );
		return s;
	}

	/**
	 * Determines whether the address is part of the array Formula range
	 */
	boolean isInRange( String addr )
	{
		return ExcelTools.isInRange( addr, rwFirst, rwLast, colFirst, colLast );
	}

	public Object getValue( PtgExp pxp )
	{
		//try{
		return FormulaCalculator.calculateFormula( expression );
		//}catch(FunctionNotSupportedException e){
		//Logger.logWarn("Array.getValue() failed: " + e);
		//return null;
		//	}
	}

	/**
	 * link this shared formula to it's parent formula
	 */
	public void setParentRec( Formula f )
	{
		parentRec = f;
	}

	/**
	 * return parent formula
	 *
	 * @return
	 */
	public Formula getParentRec()
	{
		return parentRec;
	}

	/**
	 * return the string representation of the array formula
	 */
	public String getFormulaString()
	{
		String expressString = FormulaParser.getExpressionString( expression );
		if( !"".equals( expressString ) )
		{
			return expressString.substring( 1 );
		}
		return "";
	}

	/**
	 * allow access to expression
	 *
	 * @return
	 */
	public Stack getExpression()
	{
		return expression;
	}

	/**
	 * return the cells referenced by this array in string range form
	 *
	 * @return
	 */
	public String getArrayRefs()
	{
		int[] rc = new int[2];
		rc[0] = rwFirst;
		rc[1] = colFirst;
		String rowcol1 = ExcelTools.formatLocation( rc );
		rc[0] = rwLast;
		rc[1] = colLast;
		String rowcol2 = ExcelTools.formatLocation( rc );
		return rowcol1 + ":" + rowcol2;
	}
}