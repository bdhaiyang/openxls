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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Continue: Continues Long Records (3Ch)</b><br>
 * Records longer than 8228 must be split into several records.
 * <p/>
 * These records contain only data.<p><pre>
 * <p/>
 * offset  name        size    contents
 * ---
 * 4       grBit       1       (SST ONLY) Whether the first UNICODESTRING
 * segment is compressed or uncompressed data
 * 5                   var     Continuation of record data.
 * <p/>
 * </p></pre>
 *
 * @see Sst
 * @see Txo
 */
/* more info:
 * When a SST record contains a string that is continued in a CONTINUE record, 
 * the description of the CONTINUE record for a BIFF 8 (Excel 97, Excel 2000, and Excel 2002 Workbook,) 
 * states that the record data continues at offset 4. 
 * This omits any comment to the effect that at offset 4 there is a grbit field 
 * holding a flag that describes the UNICODE state - compressed or uncompressed - 
 * of the portion of the string that is continued beginning at offset 5. 
 * 
 * Where any character in the data segment requires Unicode high-order byte information, 
 * the grbit flag will be 01h, and all characters in the string segment will be two-byte, 
 * uncompressed Unicode. 
 */
public final class Continue extends XLSRecord
{
	public byte grbit = 0x0;
	public BiffRec predecessor = null;
	public int grbitoff = 0;
	/**
	 * The SST record is the Shared String Table record,
	 * and will contain the strings of text from cells of the worksheet.
	 * For Excel 97, Excel 2000, Excel 2002 the size of this record is
	 * limited to 8224 bytes of data, including the formatting runs
	 * and string-length information. Shared string data that exceeds
	 * this limit will be stored in a CONTINUE record. When the last
	 * string in the SST must be broken into two segments, and the
	 * last segment stored as the first data in the CONTINUE record,
	 * that segment may be stored as either compressed or uncompressed
	 * Unicode. Consequently, the first byte of the data will contain 00h or 01h.
	 * This is a one-byte field called a grbit field. It is not part of the string segment.
	 * <p/>
	 * The grbit flag value 00h says no bytes of the data need
	 * Unicode high-order byte data, so all are stored as compressed Unicode
	 * (all the high-order bytes of the Unicode representation of the data
	 * characters have been stripped. They all contained 00h, so Excel manages
	 * the logic of restoring that high-order information when it loads the record.)
	 * <p/>
	 * Where any character in the data segment requires Unicode
	 * high-order byte information, the grbit flag will be 01h,
	 * and all characters in the string segment will be two-byte,
	 * uncompressed Unicode.
	 */

	Boolean hasgrbit = null;
	MSODrawing maskedMso = null;    // Continue's can mask Mso's-this links to Masked Mso rec created from this data; see ContinueHandler.addRec;
	byte[] deldata = null;
	boolean streaming = false;
	private static final Logger log = LoggerFactory.getLogger( Continue.class );
	private static final long serialVersionUID = -6303887828816619839L;
	private byte mygr;
	private int continue_offset = -1;

	/**
	 * creates and returns a basic Continues record which defines a text string
	 *
	 * @param txt - String txt to represent
	 * @return new Continue record
	 */
	public static Continue getTextContinues( String txt )
	{
		Continue c = new Continue();
		c.setOpcode( CONTINUE );
		byte[] data = new byte[txt.getBytes().length + 1];
		System.arraycopy( txt.getBytes(), 0, data, 1, data.length - 1 );
		c.setData( data );
		c.init();
		return c;
	}

	/**
	 * create and return the absolute minimum Continue record defining a formatting run
	 *
	 * @return new Continue record
	 */
	public static Continue getBasicFormattingRunContinues()
	{
		/**
		 * 0 2 First formatted character (zero-based)
		 2 2 Index to FONT record (➜5.45)
		 */
		Continue c = new Continue();
		c.setOpcode( CONTINUE );
		c.setData( new byte[4] );    // meaning: from character 0 to the end, use default font 0
		c.init();
		return c;
	}

	@Override
	public void init()
	{
		super.init();
		streaming = false;
		mygr = super.getByteAt( 0 );
			log.trace( " init() GRBIT: {}", String.valueOf( mygr ) );
	}

	/**
	 * set the streaming flag so we get the grbit in output
	 */
	@Override
	public void preStream()
	{
		streaming = true;
	}

	/**
	 * Override this to not return the grbit as part of Continue data
	 */
	@Override
	public byte[] getData()
	{
		if( getHasGrbit() && !streaming )
		{
			super.getData();
			return getBytesAt( 0, getLength() - 4 );
		}
		streaming = false;
		return super.getData();
	}

	@Override
	public byte getByteAt( int off )
	{
		int s = off;
		int rpos = s + grbitoff;
		if( rpos < 0 )
		{
				log.trace( "Continue pointer is: " + rpos );
			rpos = 0;
		}
		rpos -= continue_offset;
		return super.getByteAt( rpos );

	}

	/**
	 * @return
	 */
	public int getContinueOffset()
	{
		return continue_offset;
	}

	/**
	 * @param i
	 */
	public void setContinueOffset( int i )
	{
		continue_offset = i;
	}

	/**
	 * @return
	 */
	public BiffRec getPredecessor()
	{
		return predecessor;
	}

	void setPredecessor( BiffRec pr )
	{
		predecessor = pr;
	}

	boolean isBigRecContinue()
	{
		if( predecessor == null )
		{
			return true;
		}
		if( predecessor.getLength() >= MAXRECLEN )
		{
			return true;
		}
		return false;
	}

	boolean getHasGrbit()
	{
		if( hasgrbit != null )
		{
			return hasgrbit;
		}

		log.trace( "Grbit pos0: " + String.valueOf( getGrbit() & 0 ) );
		log.trace( "Grbit pos2: " + String.valueOf( getGrbit() & 4 ) );
		log.trace( "Grbit pos3: " + String.valueOf( getGrbit() & 8 ) );

		if( (getGrbit() & 8) != 0 )
		{
			return true; // ie Bit is set
		}

		return ((getGrbit() < 0x2) && (getGrbit() >= 0x0));
	}

	/**
	 * Set that this continue has a grbit.  I find this method very odd in that
	 * when one is in a continue record, it backs off the amount of the continue offset to the beginning of the
	 * sst record that is being handled.  In cases of encrypted workbooks this causes a failure.  I am not exactly enamored
	 * of this fix, but the whole code section seems off.
	 *
	 * @param b
	 */
	public void setHasGrbit( boolean b )
	{
		if( b && (getEncryptedByteReader().equals( getByteReader() )) )
		{
			grbit = getByteAt( 0 );
		}
		hasgrbit = b;
	}

	byte getGrbit()
	{
		if( data != null )
		{
			return data[0];
		}
		return super.getByteAt( 0 );
	}
}
