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
package org.openxls.formats.XLS.formulas;

import org.openxls.formats.XLS.Formula;
import org.openxls.formats.XLS.FormulaNotFoundException;
import org.openxls.formats.XLS.Name;
import org.openxls.formats.XLS.WorkBook;
import org.openxls.toolkit.ByteTools;
import org.openxls.toolkit.FastAddVector;
import org.openxls.formats.XLS.Array;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This PTG stores an index to a name.  The ilbl field is a 1 based index to the table
 * of NAME records in the workbook
 * <p/>
 * OFFSET      NAME        sIZE        CONTENTS
 * ---------------------------------------------
 * 0           ilbl        2           Index to the NAME table
 * 2           (reserved)  2   `       Must be 0;
 *
 * @see Ptg
 * @see org.openxls.formats.XLS.Formula
 */
public class PtgName extends GenericPtg implements Ptg, IlblListener
{
	private static final Logger log = LoggerFactory.getLogger( PtgName.class );
	private static final long serialVersionUID = 8047146848365098162L;
	short ilbl;
	String name;

	@Override
	public boolean getIsOperand()
	{

		return true;
	}

	// lookup Name object  in Workbook and return handle
	public Name getName()
	{
		WorkBook b = getParentRec().getWorkBook();
		Name n = null;
		try
		{
			n = b.getName( ilbl );
		}
		catch( Exception ex )
		{
		}
		return n;
	}

	@Override
	public void init( byte[] b )
	{
		ptgId = b[0];
		record = b;
		populateVals();
		addToRefTracker();
	}

	//default constructor
	public PtgName()
	{
		ptgId = 0x23;        // reference type is default
	}

	/**
	 * set the Ptg Id type to one of:
	 * VALUE, REFERENCE or Array
	 * <br>The Ptg type is important for certain
	 * functions which require a specific type of operand
	 */
	public void setPtgType( short type )
	{
		switch( type )
		{
			case VALUE:
				ptgId = 0x43;
				break;
			case REFERENCE:
				ptgId = 0x23;
				break;
			case ARRAY:
				ptgId = 0x63;
				break;
		}
		record[0] = ptgId;
	}

	// 20100218 KSC:
	// constructor which sets a specific id
	// to specify whether this PtgName is of value, ref or array type
	// (PtgNameV, PtgNameR or PtgNameA)
	public PtgName( int id )
	{
		ptgId = (byte) id;
//				0x23=   Ref		
//		ptgId = 0x43;	Value
	}

	/**
	 * add this reference to the ReferenceTracker... this
	 * is crucial if we are to update this Ptg when cells
	 * are changed or added...
	 */
	public void addToRefTracker()
	{
		//Logger.logInfo("Adding :" + this.toString() + " to tracker");
		try
		{
			if( parent_rec != null )
			{
				parent_rec.getWorkBook().getRefTracker().addPtgNameReference( this );
			}
		}
		catch( Exception ex )
		{
			log.error( "PtgRef.addToRefTracker() failed.", ex );
		}
	}

	/**
	 * For creating a ptg name from formula parser
	 */
	public void setName( String name )
	{
		record = new byte[5];
		record[0] = ptgId;
		WorkBook b = getParentRec().getWorkBook();
		ilbl = (short) b.getNameNumber( name );
		addListener();
		record[1] = (byte) ilbl;
	}

	private void populateVals()
	{
		ilbl = ByteTools.readShort( record[1], record[2] );
	}

	public int getVal()
	{
		return ilbl;
	}

	@Override
	public short getIlbl()
	{
		return ilbl;
	}

	@Override
	public void storeName( String nm )
	{
		name = nm;
	}

	@Override
	public void setIlbl( short i )
	{
		if( ilbl != i )
		{
			ilbl = i;
			updateRecord();
		}
	}

	/*
	 *
	 * returns the string value of the name
		@see org.openxls.formats.XLS.formulas.Ptg#getValue()
	 */
	@Override
	public Object getValue()
	{
		Name n = getName();
		try
		{
			Ptg[] p = n.getCellRangePtgs();
			if( p.length == 0 )
			{
				return new String( "#NAME?" );
			}
			if( (p.length == 1) || !(parent_rec instanceof Array) )
			{    // usual case
				return p[0].getValue();
			} // multiple values; create an array
			String retarry = "";
			for( Ptg aP : p )
			{
				retarry = retarry + aP.getValue() + ",";
			}
			retarry = "{" + retarry.substring( 0, retarry.length() - 1 ) + "}";
			PtgArray pa = new PtgArray();
			pa.setVal( retarry );
			return pa;
		}
		catch( Exception e )
		{
		}
		;
//    	String s = n.getName();
		//return n;
		return new String( "#NAME?" );
	}

	@Override
	public String getTextString()
	{
		Name n = getName();
		if( n == null )
		{
			return "#NAME!";
		}
		return n.getName();
	}

	@Override
	public String getStoredName()
	{
		return name;
	}

	public void setVal( int i )
	{
		ilbl = (short) i;
		updateRecord();
	}

	@Override
	public void updateRecord()
	{
		byte[] brow = ByteTools.cLongToLEBytes( ilbl );
		record[1] = brow[0];
		record[2] = brow[1];
		if( parent_rec != null )
		{
			if( parent_rec instanceof Formula )
			{
				((Formula) parent_rec).updateRecord();
			}
		}
	}

	/**
	 * Override due to mystery extra byte
	 * occasionally found in ptgName recs.
	 */
	@Override
	public int getLength()
	{
		if( record != null )
		{
			return record.length;
		}
		return PTG_NAME_LENGTH;
	}

	public String toString()
	{
		if( getName() != null )
		{
			return getName().getName();
		}
		return "[Null]";
	}

	@Override
	public Ptg[] getComponents()
	{
		FastAddVector v = new FastAddVector();
		Ptg p = getName().getPtga();
		Ptg[] pcomps = p.getComponents();
		if( pcomps != null )
		{
			for( Ptg pcomp : pcomps )
			{
				v.add( pcomp );
			}
		}
		else
		{
			v.add( p );
		}
		Ptg[] retPtgs = new Ptg[v.size()];
		retPtgs = (Ptg[]) v.toArray( retPtgs );
		return retPtgs;

/*    	Ptg[] p = this.getName().getComponents();
    	for (int i=0;i<p.length;i++){
    	    Ptg[] pcomps = p[i].getComponents();
    		if (pcomps!= null){
    			for (int x=0;x<pcomps.length;x++){
    				v.add(pcomps[x]);    				
    			}
    		}else{
    			v.add(p[i]);
    		}
    	}
    	Ptg[] retPtgs = new Ptg[v.size()];
    	retPtgs = (Ptg[])v.toArray(retPtgs);
    	return retPtgs;*/
	}

	/**
	 * return referenced Names' location
	 *
	 * @see GenericPtg#getLocation()
	 */
	@Override
	public String getLocation() throws FormulaNotFoundException
	{
		if( getName() != null )
		{
			try
			{
				return getName().getLocation();
			}
			catch( Exception e )
			{
				;
			}
		}
		return null;
	}

	@Override
	public void addListener()
	{
		Name n = getName();
		if( n != null )
		{
			n.addIlblListener( this );
			storeName( n.getName() );
		}

	}
}
    
    