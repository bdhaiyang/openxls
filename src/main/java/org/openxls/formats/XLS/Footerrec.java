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
 * <b>Footerrec: Print Footer on Each Page (15h)</b><br>
 * <p/>
 * Footerrec describes the header printed on every page
 * <p/>
 * <p><pre>
 * offset  name            size    contents
 * ---
 * 4       cch             1       Length of the Footer String
 * 5       rgch            var     Footer String
 * <p/>
 * </p></pre>
 */
public final class Footerrec extends XLSRecord
{
	private static final Logger log = LoggerFactory.getLogger( Footerrec.class );
	private static final long serialVersionUID = 227652250172483965L;
	int cch = -1;
	String rgch = "";

	@Override
	public void setSheet( Sheet bs )
	{
		super.setSheet( bs );
		bs.setFooter( this );
	}

	/**
	 * set the footer text
	 * The key here is that we need to construct an excel formatted unicode string, so there will be 2 differing length fields.
	 * <p/>
	 * Yes, this will probably be an issue in Japan some day....
	 */
	public void setFooterText( String t )
	{
		try
		{
			if( ByteTools.isUnicode( t ) )
			{
				byte[] bts = t.getBytes( UNICODEENCODING );
				cch = bts.length / 2;
				byte[] newbytes = new byte[cch + 3];
				byte[] cchx = ByteTools.shortToLEBytes( (short) cch );
				newbytes[0] = cchx[0];
				newbytes[1] = cchx[1];
				newbytes[2] = 0x1;
				System.arraycopy( bts, 0, newbytes, 3, bts.length );
				setData( newbytes );
			}
			else
			{
				byte[] bts = t.getBytes( DEFAULTENCODING );
				cch = bts.length;
				byte[] newbytes = new byte[cch + 3];
				byte[] cchx = ByteTools.shortToLEBytes( (short) cch );
				newbytes[0] = cchx[0];
				newbytes[1] = cchx[1];
				newbytes[2] = 0x0;
				System.arraycopy( bts, 0, newbytes, 3, bts.length );
				setData( newbytes );
			}
		}
		catch( Exception e )
		{
			log.warn( "setting Footer text failed: " + e );
		}
		rgch = t;
	}

	/**
	 * get the footer text
	 */
	public String getFooterText()
	{
		return rgch;
	}

	@Override
	public void init()
	{
		super.init();
		if( getLength() > 4 )
		{
			int cch = getByteAt( 0 );
			byte[] namebytes = getBytesAt( 0, getLength() - 4 );
			Unicodestring fstr = new Unicodestring();
			fstr.init( namebytes, false );
			rgch = fstr.toString();
				log.debug( "Footer text: " + getFooterText() );
		}
	}

}