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

import org.openxls.toolkit.ByteTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>PROTECT: Protection Flag (12h)</b><br>
 * <p/>
 * PROTECT stores the protection state for a sheet or workbook
 * <p/>
 * <p><pre>
 * offset  name            size    contents
 * ---
 * 4       fLock           2       = 1 if the sheet or book is protected
 * </p></pre>
 */
public final class Protect extends XLSRecord
{
	private static final Logger log = LoggerFactory.getLogger( Protect.class );
	private static final long serialVersionUID = -2455962145433645632L;
	private int fLock = 0;

	/**
	 * returns whether this sheet or workbook is protected
	 */
	boolean getIsLocked()
	{
		return fLock != 0;
	}

	/**
	 * default constructor
	 */
	Protect()
	{
		super();
		byte[] bs = new byte[2];
		setOpcode( PROTECT );
		setLength( (short) 2 );
		//   setLabel("PROTECT" + String.valueOf(this.offset));
		setData( bs );
		originalsize = 2;
	}

	void setLocked( boolean b )
	{
		fLock = b ? 1 : 0;
		byte[] data = getData();
		data[0] = (byte) fLock;
	}

	@Override
	public void init()
	{
		super.init();
		fLock = ByteTools.readShort( getByteAt( 0 ), getByteAt( 1 ) );
		if( getIsLocked()  )
		{
			log.debug( "Workbook/Sheet Protection Enabled." );
		}
	}

}