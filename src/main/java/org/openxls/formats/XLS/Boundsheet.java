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

import org.openxls.ExtenXLS.Cell;
import org.openxls.ExtenXLS.CellRange;
import org.openxls.ExtenXLS.ChartHandle;
import org.openxls.ExtenXLS.ColHandle;
import org.openxls.ExtenXLS.CommentHandle;
import org.openxls.ExtenXLS.ExcelTools;
import org.openxls.ExtenXLS.FormatHandle;
import org.openxls.ExtenXLS.ImageHandle;
import org.openxls.ExtenXLS.PrinterSettingsHandle;
import org.openxls.ExtenXLS.WorkBookHandle;
import org.openxls.ExtenXLS.WorkSheetHandle;
import org.openxls.formats.OOXML.OneCellAnchor;
import org.openxls.formats.OOXML.SheetPr;
import org.openxls.formats.OOXML.SheetView;
import org.openxls.formats.OOXML.Text;
import org.openxls.formats.OOXML.TwoCellAnchor;
import org.openxls.formats.XLS.charts.Chart;
import org.openxls.formats.XLS.charts.Fontx;
import org.openxls.formats.XLS.charts.GenericChartObject;
import org.openxls.formats.XLS.formulas.FormulaParser;
import org.openxls.formats.XLS.formulas.Ptg;
import org.openxls.formats.XLS.formulas.PtgRef;
import org.openxls.formats.cellformat.CellFormatFactory;
import org.openxls.toolkit.ByteTools;
import org.openxls.toolkit.CompatibleVector;
import org.openxls.toolkit.FastAddVector;
import org.openxls.toolkit.StringTool;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.Stack;
import java.util.TreeMap;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * <b>Boundsheet: WorkSheet Information 0x85</b><br>
 * <p/>
 * This record stores the sheet name, type and stream position.
 * <p><pre>
 * offset  name        size    contents
 * ---
 * 4       lbPlyPos    4       Stream position of the BOF for the sheet
 * 8       grbit       2       Option flags
 * 10      cch         1       Length of sheet name
 * 11      grbitChr    1       Compressed/Uncompressed Unicode
 * 12      rgch        var     Sheet name
 * </p></pre>
 * <p/>
 * ----  File Layout ----
 * <p/>
 * BOUNDSHEET
 * Bof
 * Index
 * Row1 is first index in DBCELL
 * Row2 is the offset of any DBCELLS
 * Row...
 * CELLREC
 * CELLREC
 * CELLREC
 * <p/>
 * DBCELL rgdb rg rg rg rg
 * ROWS
 * CELLS
 * DBCELL rg rg rg
 * ...
 * EOF
 * <p/>
 * ----------------------
 * <p/>
 * lbplypos used to be the most important thing on earth.  now it is not an issue.
 *
 * @see WorkBook
 * @see Index
 * @see Dbcell
 * @see Row
 * @see Cell
 * @see XLSRecord
 */
public final class Boundsheet extends XLSRecord implements Sheet
{
	// hidden states from grbit field offset 1
	public static final byte VISIBLE = 0x00;
	public static final byte HIDDEN = 0x01;
	public static final byte VERY_HIDDEN = 0x02;
	public HashMap<ImageHandle, Integer> imageMap = new HashMap<ImageHandle, Integer>();
	public int lastObjId = 0;    // 20100210 KSC: track last-used Object id for this sheet
	public boolean fastCellAdds = false; // performance setting which skips safety checks
	protected AbstractList<Array> arrayformulas = new ArrayList<Array>();    // trap array formulas that span one or more cells
	protected Headerrec hdr;
	protected Footerrec ftr;
	// sheet types from grbit field offset 0
	static final byte SHEET_DIALOG = 0x00;
	static final byte XL4_MACRO = 0x01;
	static final byte CHART = 0x02;
	static final byte VBMODULE = 0x06;
	List mc = new CompatibleVector();
	boolean selected = false;
	/**
	 * given sheet.xml input stream, parse OOXML into the current sheet
	 *
	 * @param bk
	 * @param sheet
	 * @param ii
	 * @param sst               The sst.
	 * @param formulas          Arraylist stores all formulas/info - must be added after all sheets and cells
	 * @param hyperlinks
	 * @param inlineStrs        Hashmap stores inline strings and cell addresses; must be added after all sheets and cells
	 * @throws IOException
	 * @throws XmlPullParserException
	 * @throws CellNotFoundException
	 */
	HashMap<String, String> shExternalLinkInfo = null;
	private static final org.slf4j.Logger log = LoggerFactory.getLogger( Boundsheet.class );
	/**
	 *
	 */
	private static final long serialVersionUID = 8977216410574107840L;
	private Bof mybof = null;
	private Eof myeof = null;
	private String sheetname = "";
	private String sheetHash = "";
	private Map<Integer, Row> rows = new LinkedHashMap<>();
	private SortedMap<CellAddressible, BiffRec> cellsByRow = new TreeMap<>( new CellAddressible.RowMajorComparator() );
	//	private SortedMap<CellAddressible, BiffRec> cellsByCol = new TreeMap<>( new CellAddressible.ColumnMajorComparator() );
	private CellsByCol cellsByCol = new CellsByColImpl();
	private Map<String, String> arrFormulaLocs = new HashMap<String, String>();    // use for trapping array formula refs to original cell reference   [OOXML Array Formulas]
	private SortedMap<ColumnRange, Colinfo> colinfos = new TreeMap<>( new ColumnRange.Comparator() );
	private AbstractList SheetRecs = new ArrayList();
	private AbstractList localrecs;
	/**
	 * Records containting various bits of print setup.
	 */
	private List<BiffRec> printRecs;
	// These records are for boundsheet transferral to a new book.
	private List transferXfs = new ArrayList();
	private List transferFonts = new ArrayList();
	private List<Chart> charts = new ArrayList<Chart>(); // chart specific for this sheet
	private long lbPlyPos;
	private short grbit;
	private byte cch;
	private byte grbitChr;
	// private int sheetnum;
	private Index myidx;
	private BiffRec lastCell;
	private Row lastRow = null;
	private Window2 win2;
	private Scl scl;
	private Pane pane;
	private Dval dval;
	private WsBool wsbool;
	private Guts guts;
	private boolean formulaShiftInclusive = false;
	private AbstractList cond_formats = new Vector();
	private AbstractList<AutoFilter> autoFilters = new Vector<AutoFilter>(); // 20100111 KSC
	// OOXML use: stores external sheet-level OOXML objects
	private AbstractList ooxmlObjects = new ArrayList();
	// OOXML-specific sheet attributes	TODO: translate to Excel 2003 version IF POSSIBLE
	private boolean thickBottom = false;
	private boolean thickTop = false;
	private boolean zeroHeight = false;
	private boolean customHeight = false;
	private double defaultRowHeight = 12.75; // measured in point size
	private float defaultColWidth = (float) -1.0;
	private DefColWidth defColWidth = null;
	private HashMap ooxmlShapes = null; // stores OOXML shapes
	private SheetView sheetview = null;
	private SheetPr sheetPr = null;
	private org.openxls.formats.OOXML.AutoFilter ooautofilter = null;
	private SheetProtectionManager protector;
	private transient HashMap sheetNameRecs = new HashMap(); // sheet scoped names
	private Selection lastselection = null;
	/**
	 * get the min/max dimensions
	 * for this sheet.
	 */
	private Dimensions dimensions;
	private boolean copypriorformats = true;
	private int maximumCellCol = -1;
	private int maximumCellRow = -1;

	/**
	 * A simple enum to store matching strings, format patterns, and value
	 * switches where necessary
	 */
	private enum NumberAsStringFormat
	{
		PERCENT( "%", "0%" ),
		EURO( "€", "€#,##0;(€#,##0)" ),
		YEN( "¥", "¥#,##0;(¥#,##0)" ),
		POUND( "£", "£#,##0;(£#,##0)" ),
		DOLLAR( "$", "$#,##0;(€#,##0)" ),
		ALT_POUND( "₤", "₤#,##0;(₤#,##0)" );

		private final String identifier;
		private final String pattern;

		private NumberAsStringFormat( String id, String format )
		{
			identifier = id;
			pattern = format;
		}

		public String identifier()
		{
			return identifier;
		}

		public String pattern()
		{
			return pattern;
		}

		/**
		 * adjust the value where necessary *
		 */
		public double adjustValue( double inputVal )
		{
			if( identifier.equals( "%" ) )
			{
				return inputVal * .01;
			}
			return inputVal;
		}
	}

	/**
	 * associates external reference info with the r:id of the external reference
	 * for instance, oleObject elements are associated with a shape Id that links back to a .vml file entry
	 *
	 * @param externalobjs
	 * @param xpp
	 */
	protected static void addExternalInfo( Map<String, String> externalobjs, XmlPullParser xpp )
	{
		//String[] attrs= new String[xpp.getAttributeCount()-1];
		ArrayList<String> attrs = new ArrayList<String>();
		String rId = "";
		//int j= 0;
		for( int i = 0; i < xpp.getAttributeCount(); i++ )
		{
			String n = xpp.getAttributeName( i );
			if( n.equals( "id" ) )
			{
				rId = xpp.getAttributeValue( i );
			}
			else
			//attrs[j++]= n+ "=\"" + xpp.getAttributeValue(i) +"\"";
			{
				attrs.add( n + "=\"" + xpp.getAttributeValue( i ) + "\"" );
			}
		}
		String s = Arrays.asList( attrs.toArray() ).toString(); // 1.6 only Arrays.toString(attrs.toArray());
		if( s.length() > 2 )
		{
			s = s.substring( 1, s.length() - 1 );
			//1.6 only s= s.replace(",", "");   // only issue is embedded ,'s in quoted strings, lets assume not!
			s = StringTool.replaceText( s, ",", "" );  // only issue is embedded ,'s in quoted strings, lets assume not!
		}
		externalobjs.put( rId, s );
	}

	/**
	 * for numbers stored as strings, try to guess the
	 * format pattern used, and strip the value to a number
	 * <p/>
	 * TODO: increase sophistication of pattern matching to better guess pattern used
	 *
	 * @param s
	 * @return Object[Double value, String formatPattern]
	 */
	static Object[] fixNumberStoredAsString( Object s ) throws NumberFormatException
	{
		String input = s.toString();
		if( input.indexOf( " " ) > -1 )
		{
			input = StringTool.allTrim( input );
		}
		String p; // the format pattern
		boolean matched = false;

		for( NumberAsStringFormat fmts : NumberAsStringFormat.values() )
		{
			if( input.indexOf( fmts.identifier ) > -1 )
			{
				input = StringTool.strip( input, fmts.identifier );
				p = fmts.pattern;
				matched = true;
				Double d = new Double( input );
				d = fmts.adjustValue( d );
				Object[] ret = new Object[2];
				ret[0] = d; // value
				ret[1] = p; // format pattern
				return ret;
			}
		}

		throw new NumberFormatException();
	}

	/**
	 * Adjusts a cell to reflect its parent row being shifted.
	 * This adjusts any mention of the row number in the cell record. Formula
	 * references are not handled; for those see {@link ReferenceTracker}.
	 *
	 * @param cell  the cell record to be shifted
	 * @param shift the number of rows by which to shift the cell
	 */
	private static void shiftCellRow( BiffRec cell, int shift )
	{
		int newrow = cell.getRowNumber() + shift;
		cell.setRowNumber( newrow );

		// handle per-record special cases
		switch( cell.getOpcode() )
		{
			case RK:
				((Rk) cell).setMulrkRow( newrow );
				break;

			case FORMULA:
				Formula formula = (Formula) cell;

				// must also shift shared formulas if necessary
				if( formula.isSharedFormula() )
				{
					if( formula.getInternalRecords().size() > 0 )
					{// is it the parent?
						Object o = formula.getInternalRecords().get( 0 );
						if( o instanceof Shrfmla )
						{    // should!
							Shrfmla s = (Shrfmla) o;
							s.setFirstRow( s.getFirstRow() + shift );
							s.setLastRow( s.getLastRow() + shift );
						}
					}
				}
				break;
		}
	}

	public Dval getDvalRec()
	{
		return dval;
	}

	public void setDvalRec( Dval d )
	{
		dval = d;
	}

	/**
	 * Gets this sheet's SheetProtectionManager.
	 */
	public SheetProtectionManager getProtectionManager()
	{
		if( protector == null )
		{
			protector = new SheetProtectionManager( this );
		}
		return protector;
	}

	/*
	 * TODO: find calls to this method which really need to be calling 'assembleSheetRecs() -jm 8/05
     * */
	@Override
	public List getSheetRecs()
	{
		return SheetRecs;
	}

	public void setSheetRecs( AbstractList shtRecs )
	{
		SheetRecs = shtRecs;
	}

	@Override
	public Eof getMyEof()
	{
		return myeof;
	}

	@Override
	public Headerrec getHeader()
	{
		return hdr;
	}

	@Override
	public void setHeader( BiffRec h )
	{
		hdr = (Headerrec) h;
	}

	@Override
	public Footerrec getFooter()
	{
		return ftr;
	}

	@Override
	public void setFooter( BiffRec ftr )
	{
		this.ftr = (Footerrec) ftr;
	}

	/**
	 * get the last BiffRec added to this sheet
	 */
	@Override
	public BiffRec getLastCell()
	{
		return lastCell;
	}

	/**
	 * @return Returns the localrecs.
	 */
	@Override
	public List getLocalRecs()
	{
		return localrecs;
	}

	/**
	 * @param localrecs The localrecs to set.
	 */
	public void setLocalRecs( FastAddVector l )
	{
		localrecs = l;
	}

	@Override
	public Bof getMyBof()
	{
		return mybof;
	}

	/**
	 * Insert an image into the WorkBook
	 *
	 * @param im
	 */
	public void insertImage( ImageHandle im )
	{
		insertImage( im, false );
	}

	/**
	 * Please add comments for this method
	 *
	 * @param bAddUnconditionally
	 */
	public void insertImage( ImageHandle im, boolean bAddUnconditionally )
	{
		MSODrawingGroup msodg = wkbook.getMSODrawingGroup();
		MSODrawing msoDrawing = (MSODrawing) MSODrawing.getPrototype();
		msoDrawing.setSheet( this );
		msoDrawing.setCoords( im.getCoords() );

		im.setMsgdrawing( msoDrawing );        // 20070924 KSC: link 2 actual msodrawing that describes this image for setting bounds, etc.
		int insertIndex;
		Obj obj = (Obj) Obj.getPrototype();
		// now add to proper place in stream
		if( msodg != null )
		{ // already have drawing records; just add to records + update msodg
			insertIndex = getIndexOf( MSODRAWINGSELECTION );
			if( insertIndex < 0 )
			{
				insertIndex = getIndexOf( WINDOW2 );
			}
			if( msodg.getMsoHeaderRec( this ) == null )    //  handle case of multiple sheets- each needs it's own mso header ...
			{
				msoDrawing.setIsHeader();
			}
		}
		else
		{ // No images present in workbook, must add appropriate records
			// Create new msodg rec
			wkbook.setMSODrawingGroup( (MSODrawingGroup) MSODrawingGroup.getPrototype() );
			msodg = wkbook.getMSODrawingGroup();
			msodg.initNewMSODrawingGroup();    // generate and add required records for drawing records
			// also add 1st portion for drawing rec
			msoDrawing.setIsHeader();
			// insertion point for new msodrawing rec
			insertIndex = getIndexOf( DIMENSIONS ) + 1;
		}
		if( insertIndex > 0 )
		{ // should! then have a drawing record to insert
			// 20071120 KSC: retrieve idx in order to reuse/link to existing image bytes if duplicating images
			int idx = msodg.addImage( im.getImageBytes(), im.getImageType(), bAddUnconditionally );
			imageMap.put( im, im.getImageIndex() - 1 );            // add new image to map and link to actual imageIndex - moved from above
			msoDrawing.createRecord( ++wkbook.lastSPID,
			                         im.getImageName(),
			                         im.getShapeName(),
			                         idx );        // generate msoDrawing using correct values moved from above
			SheetRecs.add( insertIndex++, msoDrawing );
			SheetRecs.add( insertIndex++, obj );
			msodg.addMsodrawingrec( msoDrawing );    // add the new drawing rec to the msodrawinggroup set of recs
			wkbook.updateMsodrawingHeaderRec( this );        // find the msodrawing header record and update it (using info from other msodrawing recs)
			// 20080908 KSC: moved from above
			msodg.setSpidMax( wkbook.lastSPID + 1 ); // was ++lastSPID
			msodg.updateRecord();        // given all information, generate appropriate bytes
			msodg.dirtyflag = true;
		}
		else
		{
			log.error( "Boundsheet.insertImage:  Drawing Group not created." );
		}
	}

	/**
	 * returns the images list
	 */
	public List<ImageHandle> getImageVect()
	{
		ArrayList<ImageHandle> im = new ArrayList<ImageHandle>();
		Iterator<ImageHandle> ir = imageMap.keySet().iterator();
		while( ir.hasNext() )
		{
			im.add( ir.next() );
		}
		return im;

	}

	/**
	 * Get a collection of all names in the worksheet
	 */
	public Name[] getSheetScopedNames()
	{
		if( sheetNameRecs == null )
		{
			sheetNameRecs = new HashMap();
		}
		ArrayList a = new ArrayList( sheetNameRecs.values() );
		Name[] n = new Name[a.size()];
		a.toArray( n );
		return n;
	}

	/**
	 * for whatever reason, we return a Handle from an internal class
	 *
	 * @return
	 */
	public ImageHandle[] getImages()
	{
		/* 20071026 KSC: since there may be multiple copies of the same
		 * image in the sheet, must build imageHandle array by hand
		 */
		if( imageMap == null )
		{
			return null;
		}
		ImageHandle[] im = new ImageHandle[imageMap.size()];
		Iterator<ImageHandle> ir = imageMap.keySet().iterator();
		int i = 0;
		while( ir.hasNext() )
		{
			im[i++] = ir.next();
		}
		return im;
	}

	/**
	 * For workbooks that do not contain a dval record,
	 * insert a default dval rec
	 *
	 * @return
	 */
	public Dval insertDvalRec()
	{
		if( getDvalRec() != null )
		{
			return getDvalRec();
		}
		Dval d = (Dval) Dval.getPrototype();
		d.setSheet( this );
		int insertIdx = win2.getRecordIndex() + 1;
		// correct position for DV block is before sheet protection records (if any)
		// or before EOF
		int opc = ((BiffRec) SheetRecs.get( insertIdx )).getOpcode();
		while( opc != EOF )
		{
			if( (opc == SHEETPROTECTION) || (opc == RANGEPROTECTION) || (opc == SHEETLAYOUT) )
			{
				break;
			}
			insertIdx++;
			opc = ((BiffRec) SheetRecs.get( insertIdx )).getOpcode();
		}
		SheetRecs.add( insertIdx, d );
		setDvalRec( d );
		return d;
	}

	/**
	 * Create a dv (validation record)
	 * record gets inserted into the byte stream from
	 * within Dval
	 *
	 * @param location
	 * @return
	 */
	public Dv createDv( String location )
	{
		if( getDvalRec() == null )
		{
			insertDvalRec();
		}
		Dv dv = getDvalRec().createDvRec( location );
		int insertIdx = SheetRecs.size() - 2; // start at 1 before EOF
		int opc = ((BiffRec) SheetRecs.get( insertIdx )).getOpcode();
		while( (opc != DV) && (opc != DVAL) )
		{
			insertIdx--; // insert after last DV
			opc = ((BiffRec) SheetRecs.get( insertIdx )).getOpcode();
		}
		SheetRecs.add( insertIdx + 1, dv ); // insert after DVAL or last DV
		return dv;
	}

	/**
	 * Create a Condfmt (Conditional format) record and
	 * add it to sheet recs
	 *
	 * @param location
	 * @return
	 */
	public Condfmt createCondfmt( String location, WorkBookHandle wbh )
	{
		Condfmt cfx = (Condfmt) Condfmt.getPrototype();
		int insertIdx = win2.getRecordIndex() + 1;
		BiffRec rec = (BiffRec) SheetRecs.get( insertIdx );
		while( (rec.getOpcode() != HLINK) &&
				(rec.getOffset() != DVAL) &&
				(rec.getOpcode() != 0x0862) &&
				(rec.getOpcode() != 0x0867) &&
				(rec.getOpcode() != 0x0868) &&
				(rec.getOpcode() != EOF) )
		{
			rec = (BiffRec) SheetRecs.get( ++insertIdx );
		}

		SheetRecs.add( insertIdx, cfx );
		cfx.setStreamer( streamer );
		cfx.setWorkBook( getWorkBook() );
		cfx.resetRange( location );
		addConditionalFormat( cfx );
		cfx.setSheet( this );
		return cfx;
	}

	/**
	 * Create a Cf (Conditional format rule) record and
	 * add it to sheet recs
	 *
	 * @param Conditional format
	 * @param range
	 * @return
	 */
	public Cf createCf( Condfmt cfx )
	{
		Cf cf = (Cf) Cf.getPrototype();
		// we add this rec to vec right after its Condfmt
		int insertIdx = cfx.getRecordIndex() + 1;
		SheetRecs.add( insertIdx, cf );
		cf.setStreamer( streamer );
		cf.setWorkBook( getWorkBook() );
		cf.setSheet( this );
		cf.setCondfmt( cfx );
		cfx.addRule( cf );
		return cf;
	}

	/**
	 * obtain the desired image handle via the MsoDrawing Image Index
	 * used for mapping images from copied worksheets
	 *
	 * @param index
	 * @return
	 */
	public ImageHandle getImageByMsoIndex( int index )
	{
		if( imageMap == null )
		{
			return null;
		}
		Iterator<ImageHandle> ir = imageMap.keySet().iterator();
		ImageHandle ret = null;
		while( ir.hasNext() && (ret == null) )
		{
			ImageHandle im = ir.next();
			if( im.getMsodrawing().getImageIndex() == index )
			{
				ret = im;
			}
		}
		return ret;
	}

	public int getIndexOfMsodrawingselection()
	{
		BiffRec rec;

		int size = SheetRecs.size();
		int foundIndex = -1;
		for( int i = 0; i < size; i++ )
		{
			rec = (BiffRec) SheetRecs.get( i );
			if( rec instanceof MSODrawingSelection )
			{
				foundIndex = i;
				break;
			}
		}
		return foundIndex;
	}

	public int getIndexOfWindow2()
	{
		BiffRec rec;
		int size = SheetRecs.size();
		int foundIndex = -1;
		for( int i = 0; i < size; i++ )
		{
			rec = (BiffRec) SheetRecs.get( i );
			if( rec instanceof Window2 )
			{
				foundIndex = i;
				break;
			}
		}
		return foundIndex;
	}

	public int getIndexOfDimensions()
	{
		BiffRec rec;
		int size = SheetRecs.size();
		int foundIndex = -1;
		for( int i = 0; i < size; i++ )
		{
			rec = (BiffRec) SheetRecs.get( i );
			if( rec instanceof Dimensions )
			{
				foundIndex = i + 1;
				break;
			}
		}
		return foundIndex;
	}

	// Generic getIndexOf - replace specific hardocoded cases ...
	public int getIndexOf( short opc )
	{
		BiffRec rec;

		int size = SheetRecs.size();
		int foundIndex = -1;
		for( int i = 0; (i < size) && (foundIndex == -1); i++ )
		{
			rec = (BiffRec) SheetRecs.get( i );
			if( rec.getOpcode() == opc )
			{
				foundIndex = i;
			}
		}
		return foundIndex;
	}

	@Override
	public void setWindow2( Window2 w )
	{
		win2 = w;
	}

	/**
	 * return the desired record from the sheetrecs, or null if doesn't exist
	 *
	 * @param opc
	 * @return
	 */
	public BiffRec getSheetRec( short opc )
	{
		BiffRec rec;

		int size = SheetRecs.size();
		int foundIndex = -1;
		for( int i = 0; (i < size) && (foundIndex == -1); i++ )
		{
			rec = (BiffRec) SheetRecs.get( i );
			if( rec.getOpcode() == opc )
			{
				return rec;
			}
		}
		return null;
	}

	// 20070916 KSC: access for inserting records into sheetrecs collection
	public void insertSheetRecordAt( BiffRec r, int index )
	{
		r.setSheet( this );
		if( (index > -1) && (index < SheetRecs.size()) )
		{
			SheetRecs.add( index, r );
		}
		else
		{
			SheetRecs.add( r );
		}
	}

	/**
	 * Sheet hash is a cross-workbook identifier for ExtenXLS.  The first time it is called it creates the sheet hash.
	 *
	 * @return
	 */
	public String getSheetHash()
	{
		if( sheetHash.equals( "" ) )
		{
			sheetHash = getSheetName() + getSheetNum() + getRealRecordIndex();
		}
		return sheetHash;
	}

	@Override
	public Window2 getWindow2()
	{
		return win2;
	}

	/**
	 * assembleSheetRecs assembles the array of records, then ouputs
	 * the ordered list to the bytestreamer, which should be the only
	 * thing calling this.
	 */
	public List assembleSheetRecs()
	{
		return WorkBookAssembler.assembleSheetRecs( this );
	}

	/**
	 * write this sheet as tabbed text output: <br>
	 * All rows and all characters in each cell are saved. Columns of data are
	 * separated by tab characters, and each row of data ends in a carriage
	 * return. If a cell contains a comma, the cell contents are enclosed in
	 * double quotation marks. All formatting, graphics, objects, and other
	 * worksheet contents are lost. The euro symbol will be converted to a
	 * question mark. If cells display formulas instead of formula values, the
	 * formulas are saved as text.
	 */
	/*
	 * From Excel: To preserve the formulas if you reopen the file in Microsoft
	 * Excel, select the Delimited option in the Text Import Wizard, and select
	 * tab characters as the delimiters. Note If your workbook contains special
	 * font characters, such as a copyright symbol (©), and you will be using
	 * the converted text file on a computer with a different operating system,
	 * save the workbook in the text file format appropriate for that system.
	 * For example, if you are using Windows and want to use the text file on a
	 * Macintosh computer, save the file in the Text (Macintosh) format. If you
	 * are using a Macintosh computer and want to use the text file on a system
	 * running Windows or Windows NT, save the file in the Text (Windows)
	 * format.
	 */
	public void writeAsTabbedText( OutputStream dest ) throws IOException
	{
		int lastrow = getMaxRow();
		int lastcol = getMaxCol();
		boolean isInteger = false;
		byte[] tab = { 9 };
		byte[] crlf = { 13, 10 };
		for( int i = 0; i < lastrow; i++ )
		{
			Row r = getRowByNumber( i );
			if( r != null )
			{
				for( int j = 0; j < lastcol; j++ )
				{
					BiffRec c;
					try
					{
						// Look for the cell and output
						c = r.getCell( (short) j );
						int type = ((XLSRecord) c).getCellType();
						Object o;
						if( type != Cell.TYPE_FORMULA )
						{
							isInteger = (type == Cell.TYPE_INT);
							o = c.getStringVal();
						}
						else
						{
							o = ((Formula) c).calculateFormula();
							if( (o instanceof Integer) || ((o instanceof Double) && (((Double) o).intValue() == (Double) o)) )
							{
								isInteger = true;
							}
							else
							{
								isInteger = false;
							}
						}
						try
						{
							String output = CellFormatFactory.fromPatternString( c.getFormatPattern() ).format( o.toString() );
							if( output.indexOf( "," ) != -1 )
							{
								output = "\"" + output + "\"";
							}
							dest.write( output.getBytes() );
						}
						catch( Exception e )
						{
							log.warn( "Boundsheet.writeAsTabbedText: error writing " + c.getCellAddress() + ":" + e.toString() );
						}
					}
					catch( CellNotFoundException e1 )
					{
						// No cell exists at this location, continue
					}
					dest.write( tab );
				}
				//} catch (RowNotFoundException e) { }
			}
			dest.write( crlf );
		}
		dest.flush();
		dest.close();
	}

	/**
	 * Return an array of all the dvRecs within
	 * this boundsheet (Dval parent rec)
	 *
	 * @return
	 */
	public List getDvRecs()
	{
		if( getDvalRec() != null )
		{
			return getDvalRec().getDvs();
		}
		return null;
	}

	/**
	 * @return conditional formats for this sheet
	 */
	public List getConditionalFormats()
	{
		return cond_formats;
	}

	/**
	 * add a new Condtional Format rec for this sheet
	 *
	 * @param cf
	 */
	public void addConditionalFormat( Condfmt cf )
	{
		if( cond_formats == null )
		{
			cond_formats = new ArrayList();
		}
		if( cond_formats.indexOf( cf ) == -1 )
		{
			cond_formats.add( cf );
		}
	}

	/**
	 * retrieve the Pane rec for this sheet
	 *
	 * @return
	 */
	public Pane getPane()
	{
		return pane;
	}

	/**
	 * set/save the Pane rec for this sheet
	 * also links the Window2 rec to the pane rec
	 *
	 * @param p
	 */
	public void setPane( Pane p )
	{
		if( p == null )
		{ // adds new
			p = (Pane) Pane.getPrototype();
			int insertIdx = win2.getRecordIndex() + 1;
			SheetRecs.add( insertIdx, p );
		}
		pane = p;
		pane.setWindow2( win2 );
	}

	/**
	 * Remove a BiffRec from this WorkSheet.
	 *
	 * @deprecated Use {@link #removeCell(int, int)} instead.
	 */
	@Override
	public void removeCell( String celladdr )
	{
		BiffRec c = getCell( celladdr );
		if( c != null )
		{
			removeCell( c );
		}
	}

	/**
	 * remove pane rec, effectively unfreezing
	 */
	public void removePane()
	{
		SheetRecs.remove( pane );
		pane = null;
	}

	/**
	 * Remove a BiffRec from this WorkSheet.
	 */
	public void removeCell( int row, int col )
	{
		BiffRec c;
		try
		{
			c = getCell( row, col );
			removeCell( c );
		}
		catch( CellNotFoundException e )
		{
			// cell does not exist, this is fine
		}
	}

	/**
	 * removes an image from the imagehandle cache (should be in WSH)
	 * <p/>
	 * Jan 22, 2010
	 *
	 * @param img
	 * @return
	 */
	public boolean removeImage( ImageHandle img )
	{
		return imageMap.remove( img ) != null;
	}

	/**
	 * remove a record from the vector via it's index
	 * into the SheetRecs aray, includes firing a change event
	 *
	 * @param idx
	 */
	public void removeRecFromVec( int idx )
	{
		try
		{
			BiffRec rec = (BiffRec) SheetRecs.get( idx );
			removeRecFromVec( rec );
		}
		catch( Exception e )
		{
			log.error( "Boundsheet.removeRecFromVec: " + e.toString() );
		}
	}

	/**
	 * remove a BiffRec from the worksheet.
	 * <p/>
	 * Unfortunately this also has to manage mulrecs
	 */
	@Override
	public void removeCell( BiffRec cell )
	{
		log.debug("removeCell: R{}C{} - {}:{}", cell.getRowNumber(), cell.getColNumber(), cell.getClass().getName(), cell.toString() );
		///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
		//
		// Update our Row & Col Collections *before* munging the cell's state...
		//
		BiffRec removedFromRowMap = cellsByRow.remove( cell );
		if( removedFromRowMap == null )
		{
			log.warn( "Attempt to remove Cell " + cell + " from cellsByRow failed..." );
		}

		// FIXME: Nasty cast for now, but I dont want to change the interface while making this change
		BiffRec removedFromColMap = cellsByCol.remove( (CellRec) cell );
		if( removedFromColMap == null )
		{
			log.warn( "Attempt to remove Cell " + cell + " from cellsByCol failed..." );
		}

		//
		// Right, now we can handle what happens with the Mulblanks (should it be removed entirely, modified, or split?)
		//
		if( cell.getOpcode() == MULBLANK )
		{
			Mulblank mulblank = (Mulblank) cell;
			short colNumber = cell.getColNumber();

			// Note: This call *modifies* the cell and screws up any Collections that contain this cell. BE WARNED.
			List<CellRec> cellRecs = mulblank.removeCell( colNumber );

			if( !cellRecs.isEmpty() )
			{
				log.debug( "Mulblank hasn't been completely removed - there are {} cells to be added back.", cellRecs.size() );

				for( CellRec cellRec : cellRecs )
				{
					//
					// Intentional object ref comparison - I want to see if the original Mulblank was returned in the List...
					//

					//noinspection ObjectEquality
					if( cellRec != mulblank )
					{
						log.debug( "Adding new record : {}", cellRec.toString() );

						// This call ultimately flows back to the Boundsheet, and the internal collections are updated at that time
						getWorkBook().addRecord( cellRec, true );
					}
					else
					{
						log.debug( "Original Mulblank still survives after cell removal: {}", cellRec.toString() );

						// We need to add our mulblank back in to our Collection since all we did was update its internal data
						cellsByCol.add( cellRec );
						cellsByRow.put( cellRec, cellRec );
					}
				}
			}
		}
		else if( cell.getOpcode() == FORMULA )
		{
			Formula f = (Formula) cell;
			wkbook.removeFormula( f );
		}
	}

	/**
	 * Removes some rows and all associated cells from this sheet.
	 * References are not handled; for those see {@link ReferenceTracker}.
	 *
	 * @param first the zero-based index of the first row to be removed
	 * @param count the number of rows to be removed
	 * @param shift whether to shift subsequent rows up to fill the empty space
	 */
	public void removeRows( int first, int count, boolean shift )
	{

		for( int rowIdx = first; rowIdx < (first + count); rowIdx++ )
		{
			//this.removeRowContents(rowIdx);

			Row row = rows.get( rowIdx );
			if( null == row )
			{
				continue;
			}

			Iterator<BiffRec> iter = row.getCells().iterator();
			while( iter.hasNext() )
			{
				BiffRec cell = iter.next();

				// This removes the cell from the Row's map without perturbing
				// the iterator. When removeCell tries to remove it later the
				// map will silently do nothing instead of throwing a CME.
				iter.remove();

				removeCell( cell );
			}

			rows.remove( rowIdx );
			removeRecFromVec( row );
		}

		// shift all following rows up to fill the gap left by the removed rows
		if( shift && !rows.isEmpty() )
		{
			int shiftBy = -1 * count;
			int lastrow = lastRow.getRowNumber();
			for( int rowIdx = first + 1; rowIdx <= lastrow; rowIdx++ )
			{
				Row row = rows.get( rowIdx );
				if( null == row )
				{
					continue;
				}

				shiftRow( row, shiftBy );
			}
		}

		// update sheet dimensions
		dimensions.setRowLast( (null != lastRow) ? lastRow.getRowNumber() : 0 );
	}

	/**
	 * Remove a row, do not shift any other rows
	 *
	 * @throws RowNotFoundException
	 */
	public void removeRowContents( int rownum ) throws RowNotFoundException
	{
		Row r = getRowByNumber( rownum );
		// First delete the desired row
		if( r != null )
		{
			Object[] cells = r.getCellArray();
			for( Object cell : cells )
			{ // adjust cell's in row
				removeCell( (BiffRec) cell );
			}
			rows.remove( rownum );
			removeRecFromVec( r );
			r = null;
		}
		else
		{
			throw new RowNotFoundException( getSheetName() + ":" + rownum );
		}

	}

	/**
	 * Removes a set of columns and their associated cells from this sheet.
	 * Optionally shifts the subsequent columns left to fill the empty space.
	 * This method only updates the sheet and cell records. It doesn't adjust
	 * references; that's handled by {@link WorkSheetHandle#removeCols}.
	 *
	 * @param first this zero-based index of the first column to be removed
	 * @param count the number of columns to remove
	 * @param shift whether to shift subsequent columns left
	 */
	public void removeCols( int first, int count, boolean shift )
	{

		if( shift )
		{
			ReferenceTracker.updateReferences( first,
			                                   count * -1,
			                                   this,
			                                   false );    //shift or expand/contract ALL affected references including named ranges
		}

		for( int colIdx = first; colIdx < (first + count); colIdx++ )
		{

			// update or remove the ColInfo record as appropriate
			Colinfo info = getColInfo( colIdx );
			if( null != info )
			{
				if( info.getColLast() < (first + count) )
				{
					if( info.getColFirst() >= first )
					{
						removeColInfo( info );
					}
					else
					{
						info.setColLast( first - 1 );
					}
				}
				else if( info.getColFirst() >= first )
				{
					info.setColFirst( first + count );
				}
			}

			// remove the cells in the column
			try
			{
				List<BiffRec> cells = getCellsByCol( colIdx );
				int cellCount = cells.size();
				for( int idx = cellCount - 1; idx >= 0; idx-- )
				{
					BiffRec cell = cells.get( idx );
					if( null == cell )
					{
						continue;
					}

					removeCell( cell );
				}
			}
			catch( CellNotFoundException e )
			{
				// This is fine, no cells in this column
			}

		}

		if( shift )
		{
			int shiftBy = -1 * count;
			int maxcol = getRealMaxCol();
			for( int colIdx = first + 1; colIdx <= maxcol; colIdx++ )
			{
				shiftCol( colIdx, shiftBy );
			}
		}

		// make sure dimensions record is correctly updated upon output
		dimensions.setColLast( getRealMaxCol() );
	}

	/**
	 * Access an arrayList of cells by column
	 *
	 * @param colNum
	 * @return
	 */
	public ArrayList<BiffRec> getCellsByCol( int colNum ) throws CellNotFoundException
	{
		List<? extends BiffRec> theCells = cellsByCol.get( colNum );
		if( theCells.size() == 0 )
		{
			throw new CellNotFoundException( sheetname, 0, col );
		}

		Iterator<? extends BiffRec> i = theCells.iterator();
		while( i.hasNext() )
		{
			BiffRec biffrec = i.next();
			if( biffrec.getOpcode() == MULBLANK )
			{
				((Mulblank) biffrec).setCurrentCell( (short) colNum );
			}
		}
		return new ArrayList<>( theCells );
	}

	/**
	 * Access an arrayList of cells by column
	 *
	 * @param colNum
	 * @return
	 */
	public ArrayList<BiffRec> getCellsByRow( int rowNum ) throws CellNotFoundException
	{
		SortedMap<CellAddressible, BiffRec> theCells = cellsByRow.subMap( new CellAddressible.Reference( rowNum, 0 ),
		                                                                  new CellAddressible.Reference( rowNum + 1, 0 ) );
		if( theCells.size() == 0 )
		{
			throw new CellNotFoundException( sheetname, 0, col );
		}

		Collection<BiffRec> cells = theCells.values();
		return new ArrayList<>( cells );
	}

	/**
	 * set the associated sheet index
	 */
	public Index getSheetIDX()
	{
		return myidx;
	}

	/**
	 * remove rec from the vector, includes firing
	 * a changeevent.
	 */
	@Override
	public void removeRecFromVec( BiffRec rec )
	{
		boolean removerec = true;
		// is it an RK, maybe part of a Mulrk??
		if( rec.getOpcode() == RK )
		{
			Rk thisrk = (Rk) rec;
			removeMulrk( thisrk );
		}
		else if( rec.getOpcode() == FORMULA )
		{
			Formula f = (Formula) rec;
			wkbook.removeFormula( f );
		}
		else if( rec.getOpcode() == LABELSST )
		{
			Labelsst lst = (Labelsst) rec;
			Sst strtable = wkbook.getSharedStringTable();
			lst.initUnsharedString();
			strtable.removeUnicodestring( lst.getUnsharedString() );
		}
		else if( rec instanceof Mulblank )
		{
			Mulblank mulblank = (Mulblank) rec;
			short colNumber = rec.getColNumber();
//			removerec = mulblank.removeCell( colNumber );
			List<CellRec> cellRecs = mulblank.removeCell( colNumber );
			for( CellRec cellRec : cellRecs )
			{
				// Intentional object reference equality check

				//noinspection ObjectEquality
				if( cellRec == mulblank )
				{
					removerec = false;
				}
				else
				{
					getWorkBook().addRecord( cellRec, true );
				}
			}
		}

		if( removerec )
		{
			if( streamer.removeRecord( rec ) )
			{
				log.debug( "Boundsheet RemoveRec Removed: " + rec.toString() );
			}
			else
			{
				if( rec instanceof Mul )
				{
					Mul mul = (Mul) rec;
					if( !mul.removed() )
					{
						log.warn( "RemoveRec failed: " + rec.getClass().getName() + " not found in Streamer Vec" );
					}
				}
				else
				{
					log.warn( "RemoveRec failed: " + rec.getClass().getName() + " not found in Streamer Vec" );
				}
			}
		}
	}

	/**
	 * set the associated sheet index
	 */
	@Override
	public void setSheetIDX( Index idx )
	{
		idx.setSheet( this );
		myidx = idx;
	}

	/**
	 * set the numeric sheet number
	 */
	@Override
	public int getSheetNum()
	{
		return wkbook.getSheetVect().indexOf( this );
	}

	/**
	 * shifts Merged cells. 10-15-04 -jm
	 */    // used???
	@Override
	public void updateMergedCells()
	{
		if( mc.size() < 1 )
		{
			return;
		}
		Iterator mcs = mc.iterator();
		while( mcs.hasNext() )
		{
			((Mergedcells) mcs.next()).update();
		}
	}

	/**
	 * Called from removeCell(), removeMulrk() handles the fact that you
	 * are trying to delete a rk that is really just a part of a Mulrk.  This
	 * is handled by truncating the mulrk at the cell, then creating individual numbers
	 * after the deleted cell.
	 */
	@Override
	public void removeMulrk( Rk thisrk )
	{
		Mulrk mymul = (Mulrk) thisrk.getMyMul();
		if( mymul != null )
		{ // Part of a mulrk. JOY!
			AbstractList vect = mymul.removeRk( thisrk );
			boolean deletemulrk = false;
			if( mymul.getColFirst() == thisrk.getColNumber() )
			{
				deletemulrk = true;
			}
			if( vect != null )
			{ // the mulrk contiued past the cell deleted
				// Create new records for each of the Rks,
				Iterator itv = vect.iterator();
				while( itv.hasNext() )
				{
					Rk temprk = (Rk) itv.next();
					temprk.setNoMul();
					String loc = temprk.getCellAddress();

					Double d = temprk.getDblVal();
					BiffRec g = getCell( loc );
					int fmt = g.getIxfe();
					g.getRow().removeCell( g );
					removeCell( loc );

					addValue( d, loc );
					getCell( loc ).setIxfe( fmt );
					streamer.removeRecord( temprk );
				}
			}
			if( deletemulrk )
			{
				mymul.removed = true;
				removeRecFromVec( mymul );
			}
		}

	}

	/**
	 * get whether this sheet is hidden upon opening (either regular or "very hidden"
	 */
	@Override
	public boolean getHidden()
	{
		if( grbit == VISIBLE )
		{
			return false;
		}
		return true;
	}

	/**
	 * set whether this sheet is hidden upon opening
	 */
	@Override
	public void setHidden( int gr )
	{
		grbit = (short) gr;
		byte[] bt = ByteTools.shortToLEBytes( grbit );
		System.arraycopy( bt, 0, getData(), 4, 2 );
	}

	/**
	 * returns the selected sheet status
	 */
	@Override
	public boolean selected()
	{
		return selected;
	}

	/**
	 * set whether this sheet is selected upon opening
	 */
	@Override
	public void setSelected( boolean b )
	{
		if( win2 != null )
		{
			win2.setSelected( b );
		}
		if( b )
		{
			getWorkBook().setSelectedSheet( this );
		}
		selected = b;
	}

	/**
	 * get the number of defined rows on this sheet
	 */
	@Override
	public int getNumRows()
	{
		return rows.size();
	}

	/**
	 * get the number of defined cells on this sheet
	 */
	@Override
	public int getNumCells()
	{
		int counter = 0;
		Set<Integer> cellset = (Set<Integer>) rows.keySet();
		Object[] rws = cellset.toArray();
		if( rws.length == 0 )
		{
			return 0;
		}
		for( Object rw1 : rws )
		{
			Row r = rows.get( rw1 );
			counter += r.getNumberOfCells();
		}
		return counter;

	}

	/**
	 * get the FastAddVector of columns defined on this sheet
	 */
	@Override
	public List getColNames()
	{
		FastAddVector retvec = new FastAddVector();
		for( int x = 0; x < getRealMaxCol(); x++ )
		{
			String c = ExcelTools.getAlphaVal( x );
			retvec.add( c );
		}
		return retvec;
	}

	/**
	 * get a handle to a specific column of cells in this sheet
	 */
	@Override
	public Colinfo getColInfo( int col )
	{
		Colinfo info = colinfos.get( new ColumnRange.Reference( col, col ) );
		if( null == info )
		{
			return null;
		}

		if( info.inrange( col ) )
		{
			return info;
		}
		return null;
	}

	/**
	 * get the Number of columns defined on this sheet
	 */
	@Override
	public int getNumCols()
	{
		return getRealMaxCol();
	}

	/**
	 * get a handle to the Row at the specified
	 * row index
	 * <p/>
	 * Zero-based Index.
	 * <p/>
	 * ie: row 0 contains cell A1
	 */
	@Override
	public Row getRowByNumber( int r )
	{
		return rows.get( r );
	}

	/**
	 * get the FastAddVector of rows defined on this sheet
	 */
	@Override
	public List getRowNums()
	{
		Set<Integer> e = rows.keySet();
		Iterator<Integer> iter = e.iterator();
		FastAddVector rownames = new FastAddVector();
		while( iter.hasNext() )
		{
			rownames.add( rownames.size(), iter.next() );
		}
		return rownames;
	}

	/**
	 * remove all Sheet records from Sheet.
	 */
	@Override
	public void removeAllRecords()
	{
		// this.setSheetRecs();
		XLSRecord[] rx = new XLSRecord[SheetRecs.size()];
		SheetRecs.toArray( rx );

		for( int t = 0; t < rx.length; t++ )
		{
			int opcode = rx[t].getOpcode();    // Handle continues masking mso's
			if( (opcode != MSODRAWING) && !((opcode == CONTINUE) && (((Continue) rx[t]).maskedMso != null)) )
			{
				removeRecFromVec( rx[t] );
			}
			else // must update MSODrawingGroup record as well ...
				if( opcode == MSODRAWING )
				{
					wkbook.msodg.removeMsodrawingrec( (MSODrawing) rx[t], this, false ); // don't remove assoc object record
				}
				else    // a Continue record masking an MSoo
				{
					wkbook.msodg.removeMsodrawingrec( ((Continue) rx[t]).maskedMso, this, false ); // don't remove assoc object record
				}
			rx[t] = null;
		}
		SheetRecs.clear();
		// System.gc();

	}

	/**
	 * return an Array of the Rows
	 */
	@Override
	public Row[] getRows()
	{
		Map<Integer, Row> rxs = new TreeMap<Integer, Row>( rows ); // treemap does ordering... LHM does not
		Row[] rarr = new Row[rxs.size()];
		return (Row[]) rxs.values().toArray( rarr );
	}

	@Override
	public BiffRec addValue( Object obj, String address )
	{
		return addValue( obj, address, false );
	}

	/*
	 *
     */

	/**
	 * Add an XLSRecord to a WorkSheet.
	 * <p/>
	 * Creates the container cell for a record, sets the default
	 * information on the valrec (ie row/col/bs), checks to see if
	 * there is a container row for the cell, if not, then it creates the
	 * row.  Finally, the cell is passed on to addCellToRowCol where it performs
	 * final initialization and is added to it's row
	 */
	@Override
	public void addRecord( BiffRec rec, int[] rc )
	{
		// check to see if there is a BiffRec already at the address add the rec to the Cell,
		// set as value if it's a val type rec

		rec.setSheet( this );// create a new BiffRec if none exists
		rec.setRowCol( rc );
		rec.setIsValueForCell( true );
		rec.setStreamer( streamer );
		rec.setWorkBook( getWorkBook() );

		if( !fastCellAdds )
		{

			Row ro;
			ro = rows.get( rc[0] );
			if( ro == null )
			{
				ro = addNewRow( rec );
			}

		}
		if( copypriorformats && !fastCellAdds )
		{
			copyPriorCellFormatForNewCells( rec );
		}

		try
		{
			addCell( (CellRec) rec );
		}
		catch( ArrayIndexOutOfBoundsException ax )
		{
			log.error( "Boundsheet.addRecord() failed. Column " + rc[1] + " is greater than Maximum column count" );
			throw new InvalidRecordException( "Adding cell failed. Column " + rc[1] + " is greater than the maximum column limit." );
		}
	}

	@Override
	public void setCopyPriorCellFormats( boolean f )
	{
		copypriorformats = f;
	}

	/**
	 * Add a cell to this boundsheet record and populate the cells array
	 *
	 * @param cell
	 */
	@Override
	public void addCell( CellRec cell )
	{
		if( cell == null )
		{
			throw new IllegalArgumentException( "CellRec cannot be null." );
		}
		cellsByRow.put( cell, cell );
		cellsByCol.add( cell );

		Row row = rows.get( cell.getRowNumber() );
		if( null == row )
		{
			row = addNewRow( cell );
		}

		row.addCell( cell );
		cell.setSheet( this );
		updateDimensions( cell.getRowNumber(), cell.getColNumber() );
	}

	/**
	 * set the Bof record for this Boundsheet
	 */
	@Override
	public void setBOF( Bof b )
	{
		mybof = b;
		b.setSheet( this );
	}

	/**
	 * column formatting records
	 * <p/>
	 * Note that it checks if exists.  This is due to externally copied boundsheets already having
	 * the record in the array when addrecord occurs.
	 */
	@Override
	public void addColinfo( Colinfo c )
	{
		if( !colinfos.containsValue( c ) )
		{
			colinfos.put( c, c );
		}
	}

	/**
	 * get  a colinfo by name
	 */
	@Override
	public Colinfo getColinfo( String c )
	{
		return getColInfo( ExcelTools.getIntVal( c ) );
	}

	/**
	 * get the Collection of Colinfos
	 */
	@Override
	public Collection<Colinfo> getColinfos()
	{
		return Collections.unmodifiableCollection( colinfos.values() );
	}

	/**
	 * Determine if the boundsheet is a chart only boundsheet
	 *
	 * @return
	 */
	@Override
	public boolean isChartOnlySheet()
	{
		if( mybof != null )
		{
			return mybof.isChartBof();
		}
		return false;
	}

	/**
	 * Gets a cell on this sheet by its Excel A1-style address.
	 *
	 * @param address the A1-style address of the cell to retrieve
	 * @return the cell record
	 * or <code>null</code> if no cell exists at the given address
	 * @deprecated Use {@link #getCell(int, int)} instead.
	 */
	@Override
	public BiffRec getCell( String address )
	{
		int[] rc = ExcelTools.getRowColFromString( address );
		try
		{
			return getCell( rc[0], rc[1] );
		}
		catch( CellNotFoundException ex )
		{
			return null;
		}
	}

	/**
	 * Gets a cell on this sheet by its row and column indexes.
	 *
	 * @param row the zero-based index of the cell's parent row
	 * @param col the zero-based index of the cell's parent column
	 * @return the cell record at the given address
	 * @throws CellNotFoundException if no cell exists at the given address
	 */
	@Override
	public BiffRec getCell( int row, int col ) throws CellNotFoundException
	{
		// get the nearest entry from the cell map
		BiffRec theCell = cellsByRow.get( new CellAddressible.Reference( row, col ) );
		if( null == theCell )
		{
			throw new CellNotFoundException( sheetname, row, col );
		}

		if( (theCell != null) && (theCell.getOpcode() == MULBLANK) )
		{
			((Mulblank) theCell).setCurrentCell( (short) col );
		}
		return theCell;
	}

	/**
	 * get an array of all cells for this worksheet
	 */
	@Override
	public BiffRec[] getCells()
	{
		Collection<BiffRec> cells = cellsByRow.values();
		return cells.toArray( new BiffRec[cells.size()] );
	}

	@Override
	public void setEOF( Eof f )
	{
		myeof = f;
	}

	@Override
	public void addMergedCellsRec( Mergedcells r )
	{
		mc.add( r );
	}

	@Override
	public List getMergedCellsRecs()
	{
		return mc;
		/* 20081031 don't add a merged cell rec automatically
	    if (mc.size()>0) {
	        return mc;
	    }
	    Mergedcells mec = (Mergedcells)Mergedcells.getPrototype();
	    mec.setSheet(this);
	    this.getStreamer().addRecordAt(mec, this.getSheetRecs().size()-1);
	    this.addMergedCellsRec(mec);
		 */
	}

	/***
	 */
	@Override
	public Mergedcells getMergedCellsRec()
	{
		if( mc.size() == 0 )
		{
			return null; // 20081031 KSC- don't automatically add new!
		}
		return (Mergedcells) getMergedCellsRecs().get( getMergedCellsRecs().size() - 1 );
	}

	/**
	 * return the pos of the Bof for this Sheet
	 */
	@Override
	public long getLbPlyPos()
	{//
		if( mybof != null )
		{
			return mybof.getLbPlyPos();
		}
		return lbPlyPos;

	}

	/**
	 * get the name of the sheet
	 */
	@Override
	public String getSheetName()
	{
		return sheetname;
	}

	/**
	 * @return Returns the grbitChr.
	 */
	@Override
	public byte getGrbitChr()
	{
		return grbitChr;
	}

	/**
	 * @param grbitChr The grbitChr to set.
	 */
	@Override
	public void setGrbitChr( byte gb )
	{
		grbitChr = gb;
	}

	/**
	 * set the pos of the Bof for this Sheet
	 */
	@Override
	public void setLbPlyPos( long newpos )
	{
		byte[] newposbytes = ByteTools.cLongToLEBytes( (int) newpos );
		System.arraycopy( newposbytes, 0, getData(), 0, 4 );
		lbPlyPos = newpos;
	}

	/**
	 * change the displayed name of the sheet
	 * <p/>
	 * Affects the following byte values:
	 * 10      cch         1       Length of sheet name
	 * 11      grbitChr    1       Compressed/Uncompressed Unicode
	 * 12      rgch        var     Sheet name
	 */
	@Override
	public void setSheetName( String newname )
	{

		cch = (byte) newname.length();
		byte[] namebytes;
		if( !ByteTools.isUnicode( newname ) )
		{
			grbitChr = 0x0;
		}
		else
		{
			grbitChr = 0x1;
		}
		try
		{
			if( grbitChr == 0x1 )
			{
				namebytes = newname.getBytes( UNICODEENCODING );
			}
			else
			{
				namebytes = newname.getBytes( DEFAULTENCODING );
			}
		}
		catch( UnsupportedEncodingException e )
		{
			namebytes = newname.getBytes();
			log.warn( "UnsupportedEncodingException in setting sheet name: " + e + " falling back to system default." );
		}
		byte[] newdata = new byte[namebytes.length + 8];
		if( data == null )
		{
			data = newdata;
		}
		else
		{
			System.arraycopy( getData(), 0, newdata, 0, 8 );
		}

		System.arraycopy( namebytes, 0, newdata, 8, namebytes.length );
		newdata[6] = cch;
		newdata[7] = grbitChr;
		setData( newdata );
		init();
	}

	/**
	 * Returns a serialized copy of this Boundsheet
	 *
	 * @throws IOException
	 */
	@Override
	public byte[] getSheetBytes() throws IOException
	{
		setLocalRecs();
		ObjectOutputStream obs;
		byte[] b;

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		obs = new ObjectOutputStream( baos );
		obs.writeObject( this );
		b = baos.toByteArray();

		return b;
	}

	/**
	 * prior to serializing the worksheet,
	 * we need to initialize the records which belong to this sheet
	 * instance.
	 */
	@Override
	public void setLocalRecs()
	{
		localrecs = new CompatibleVector();

		List newSheetRecs = assembleSheetRecs();

		Iterator shtr = newSheetRecs.iterator();
		while( shtr.hasNext() )
		{
			try
			{
				XLSRecord x = (XLSRecord) shtr.next();
				x.getData();
				if( x instanceof Labelsst )
				{ // put the String in the label
					((Labelsst) x).initUnsharedString();
				}
				localrecs.add( x );
			}
			catch( Exception e )
			{
				log.warn( "Setting Boundsheet records problem: " + e );
			}
		}
		// add the charts to the boundsheet, as they are stored in the workbook normally.  (why?)
		charts.clear();
		Chart[] chts = getWorkBook().getCharts();
		for( Chart cht : chts )
		{
			if( cht.getSheet().equals( this ) )
			{
				charts.add( cht );
			}
		}
	}

	/**
	 * get the type of sheet as a short
	 */
	@Override
	public short getSheetType()
	{

		return grbit;
	}

	/**
	 * the beginning of the Dimensions record
	 * is the index of the RowBlocks
	 */
	@Override
	public Dimensions getDimensions()
	{
		return dimensions;
	}

	/**
	 * get the type of sheet as a string
	 */
	@Override
	public String getSheetTypeString()
	{
		switch( grbit )
		{
			case SHEET_DIALOG:
				return "Sheet or Dialog";
			case XL4_MACRO:
				return "XL4 Macro";
			case CHART:
				return "Chart";
			case VBMODULE:
				return "VB Module";
			default:
				return null;
		}
	}

	/* Inserts a serialized boundsheet into the workbook, and changes the name.
	 */
	@Override
	public Chart addChart( byte[] inbytes, String NewChartName, short[] coords )
	{
		Chart destChart = null;
		// Deserialize bytes
		try
		{
			ByteArrayInputStream bais = new ByteArrayInputStream( inbytes );
			BufferedInputStream bufstr = new BufferedInputStream( bais );
			ObjectInputStream o = new ObjectInputStream( bufstr );
			destChart = (Chart) o.readObject();
		}
		catch( Exception e )
		{
			log.info( "Boundsheet.addChart() failed:" + e );
		}
		if( destChart != null )
		{ // got chart
			if( !NewChartName.equals( "useDefault" ) )
			{
				destChart.setTitle( NewChartName ); // set new name
			}
// why do we need this??? shouldn't it be already set??			destChart.getChartFormat().setParentChart(destChart);	// make same as WorkBook.addChart
			destChart.setSheet( this );
			// BUGTRACKER 2372: chart bounds are dependent upon row+col sizes so use coordinates (which are row/col independent)
			// does it makes sense to only set h + w and NOT x and y
			short[] origCoords = destChart.getCoords();
			coords[0] = origCoords[0];    // don't set X and Y (keep to original row and column
			coords[1] = origCoords[1];
			destChart.setCoords( coords ); // but set w + h
			destChart.setId( lastObjId + 1 );    // 20100210 KSC: track last obj id per sheet ...
			HashMap localFonts = null; // fonts currently in workbook
			if( (getTransferFonts() != null) && (getTransferFonts().size() > 0) )
			{    // then must translate old font indexes to new font indexes
				localFonts = (HashMap) getWorkBook().getFontRecsAsXML();    // fonts in this workbook
			}
			List recs = destChart.getXLSrecs();
			for( Object rec1 : recs )
			{
				XLSRecord rec = (XLSRecord) rec1;
				rec.setWorkBook( wkbook );
				rec.setSheet( this );
				if( rec.getOpcode() == MSODRAWING )
				{
					wkbook.addChartUpdateMsodg( (MSODrawing) rec, this );
					continue;
				}
				if( !(rec instanceof Bof) )    // TODO: error/problem with the BOF record!!!
				{
					rec.init();
				}
				if( rec instanceof Dimensions )
				{
					destChart.setDimensions( (Dimensions) rec );
				}
				if( rec instanceof FontBasis )
				{ // 20090506 KSC: fontbasis font indexes link to subsequent text displays [added for BUGTRACKER 2372]
					int fid = ((FontBasis) rec).getFontIndex();
					// see if must translate old font indexes to new font indexes
					fid = translateFontIndex( fid, localFonts );
					((FontBasis) rec).setFontIndex( fid );
				}
				if( rec instanceof Fontx )
				{    // 20080911 KSC: must handle out of bounds font references upon chart copies [JPM BugTracker 1434]
					int fid = ((Fontx) rec).getIfnt();
					if( fid > 0 )
					{
						fid = translateFontIndex( fid, localFonts );
					}
					((Fontx) rec).setIfnt( fid );
				}
				try
				{
					((GenericChartObject) rec).setParentChart( destChart );
				}
				catch( ClassCastException e )
				{    // Scl, Obj and others are not chart objects
				}
				//try{log.info("Boundsheet Added new Chart rec:" + rec);}catch(Exception e){log.warn("Boundsheet.addChart() could not get String for rec: "+ rec.getCellAddress());}
			}
			wkbook.getChartVect().add( destChart );
		}
		charts.add( destChart );
		return destChart;
	}

	@Override
	public Guts getGuts()
	{
		return guts;
	}

	@Override
	public void setDimensions( Dimensions d )
	{
		// only set the first dimensions.  Other dimensions records may exist within
		// the boundsheet stream from charts & msodrawing objects, but going to run with the
		// assumption that the first one is the identifier for valrec start
		if( dimensions == null )
		{
			dimensions = d;
			if( myidx != null )
			{
				myidx.setDimensions( d );
			}
		}

	}

	@Override
	public void setGuts( Guts g )
	{
		guts = g;
	}

	/**
	 * Inserts a row and shifts subsequent rows down by one.
	 *
	 * @param rownum the zero-based index of the row to be created
	 * @return the row that was just inserted
	 */
	// TODO: reduce this functionality to simply inserting a row
	// and shifting the row number of subsequent rows and cells
	public Row insertRow( int rownum, int flag, boolean shiftrows )
	{
		Row roe = null;
		if( shiftrows && !fastCellAdds )
		{
			try
			{
				// shift all rows after this one down...
				// moves refs, formats, merges, etc.
				if( lastRow != null )
				{
					int startrow = lastRow.getRowNumber();
					if( startrow == MAXROWS )
					{
						startrow--; // 20080925 KSC: can't add more than maxrows
					}

					for( int t = startrow; t >= rownum; t-- )
					{        // traverse from last row to current
						Row rowtoshift = rows.get( t );
						if( rowtoshift != null )
						{
							try
							{
								shiftRow( rowtoshift, 1 );// pass original row # for formula shifting + flag
							}
							catch( Exception e )
							{
								log.warn( "Boundsheet.insertRow() failed shifting row: " + t + " - " + e.toString() );
							}
						}
					}
				}

				// we add a blank because a row cannot be empty
				roe = getRowByNumber( rownum );
				if( roe == null )
				{
					int[] rc = { rownum, 0 };
					addRecord( Blank.getPrototype(), rc );
					roe = getRowByNumber( rownum );
				}

			}
			catch( Exception a )
			{
				log.info( "Boundsheet.insertRow:  Shifting row during Insert failed: " + a );
			}
		}
		else
		{
			roe = getRowByNumber( rownum );
			if( roe == null )
			{
				Row r = new Row( rownum, wkbook );
				// must also update maxrow on sheet
				if( rownum >= getMaxRow() )
				{
					dimensions.setRowLast( rownum );
				}
				r.setSheet( this );
				addRowRec( r );
				roe = getRowByNumber( rownum );
				roe.resetCacheBytes();
			}
		}
		return roe;
	}

	/**
	 * associate an existing Row with this Boundsheet
	 * if the row already exists... ignore?
	 */
	public void addRowRec( Row r )
	{
		int rwn = r.getRowNumber();
		if( rows.containsKey( rwn ) )
		{
			log.warn( "Sheet.addRow() attempting to add existing row" );
		}
		else
		{
			rows.put( rwn, r );
			if( lastRow == null )
			{
				lastRow = r;
			}
			else if( rwn > lastRow.getRowNumber() )
			{
				lastRow = r;
			}
		}
	}

	public boolean getVeryHidden()
	{
		return (grbit == VERY_HIDDEN);
	}

	@Override
	public int getMinRow()
	{
		return dimensions.getRowFirst();
	}

	/**
	 * associate an Array formula with this Boundsheet
	 */
	public void addArrayFormula( Array a )
	{
		arrayformulas.add( a );
	}

	/**
	 * Returns an array formula for the set address
	 */
	public Array getArrayFormula( String addr )
	{
		Array form;
		for( int i = 0; i < arrayformulas.size(); i++ )
		{
			form = arrayformulas.get( i );
			if( form.isInRange( addr ) )
			{
				return form;
			}
		}
		return null;
	}

	/**
	 * map array formula range reference to the parent array formula address
	 * <br>for Array Formula Parent Records only
	 * boundsheet
	 */
	public void addParentArrayRef( String addr, String ref )
	{
		if( arrFormulaLocs.containsKey( addr ) )
		{
			log.warn( "PARENT ARRAY ALREADY FOUND" );
		}
		arrFormulaLocs.put( addr, ref );
	}

	/**
	 * return true maximum/last row on the sheet
	 */
	@Override
	public int getMaxRow()
	{
		return dimensions.getRowLast();
	}

	/**
	 * see if an array formula is part of an existing array formula
	 * <br>by checking to see if the address in quesion is
	 * referenced by any array formula references on this sheet
	 *
	 * @param rc row col of cell in question
	 * @return
	 * @see addArrayFormula
	 */
	public Object getArrayFormulaParent( int[] rc )
	{
		Iterator<String> i = arrFormulaLocs.keySet().iterator();
		while( i.hasNext() )
		{
			String addr = i.next();
			int[] arrayRC = ExcelTools.getRangeRowCol( arrFormulaLocs.get( addr ) );
			if( (rc[1] >= arrayRC[1]) && (rc[1] <= arrayRC[3]) &&
					(rc[0] >= arrayRC[0]) && (rc[0] <= arrayRC[2]) )
			{
				return arrayRC;
			}
		}
		return null; // no parent?
	}

	/**
	 * return true if address refers to an Array Formula Parent
	 * <br>i.e. the parent array formula refers to one or multiple cell addreses
	 *
	 * @param addr
	 * @return
	 */
	public boolean isArrayFormulaParent( String addr )
	{
		return (arrFormulaLocs.get( addr ) != null);
	}

	/**
	 * given an parent array formula at address formAddress,
	 * look up in saved arrFormulaLocs for the cell range it references
	 *
	 * @param formAddress
	 * @return
	 */
	public String getArrayRef( String formAddress )
	{
		return arrFormulaLocs.get( formAddress );
	}

	@Override
	public int getMinCol()
	{
		return dimensions.getColFirst();
	}

	/**
	 * inserts a col and shifts all of the other rows over one
	 *
	 * @param first zero-based int for the column (0='A')
	 */
	public void insertCols( int first, int count )
	{

		ReferenceTracker.updateReferences( first + 1,
		                                   count,
		                                   this,
		                                   false );    //shift or expand/contract ALL affected references including named ranges

		// shift the existing columns to the right to make room
		for( int colIdx = getRealMaxCol(); colIdx >= first; colIdx-- )
		{
			shiftCol( colIdx, count );
		}

		// update the new colinfos to include the formatting and the width of the inserted col
		Colinfo movedCol = getColInfo( first + count );
		if( movedCol != null )
		{
			for( int i = 0; i < count; i++ )
			{
				Colinfo newcol = getColInfo( first + i );
				if( newcol == null )
				{
					addColinfo( first + i, first + i, movedCol.getColWidth(), movedCol.getIxfe(), movedCol.getGrbit() );
				}
				else
				{
					newcol.setGrbit( movedCol.getGrbit() );
					newcol.setColWidth( movedCol.getColWidth() );
					newcol.setIxfe( movedCol.getIxfe() );
				}
			}
		}

		// ensure the sheet bounds are accurate
		dimensions.setColLast( getRealMaxCol() );
	}

	/**
	 * Add a new colinfo
	 *
	 * @param first The beginning column number (0 based)
	 * @param last  The end column number
	 * @param width Initial width of the column
	 * @param ixfe  formatting
	 * @param grbit ??
	 * @return Colinfo
	 */
	public Colinfo createColinfo( int first, int last, int width, int ixfe, int grbit )
	{
		Colinfo ci = Colinfo.getPrototype( first, last, width, ixfe );
		ci.setGrbit( grbit );
		ci.setWorkBook( getWorkBook() );
		ci.setSheet( this );
		addColinfo( ci );
		int recpos = getDimensions().getRecordIndex();
		recpos--;
		List sr = getSheetRecs();
		// get to last Colinfo record
		BiffRec rec = (BiffRec) sr.get( recpos );
		// TODO: is it ABSOLUTELY true that if no Colinfos there must be a DefColWidth record????
		while( !(rec instanceof Colinfo) &&
				!(rec instanceof DefColWidth) &&
				(recpos > 0) )
		{ // loop until we find either a colinfo or DEFCOLWIDTH
			rec = (BiffRec) sr.get( --recpos );
		}
		// now position this Colinfo in the proper position within the Colinfo set
		int cf = ci.getColFirst();
		while( (rec instanceof Colinfo) && (((Colinfo) rec).getColFirst() > cf) )
		{
			rec = (BiffRec) sr.get( --recpos );
		}
		recpos++;
		getStreamer().addRecordAt( ci, recpos );
		return ci;
	}

	/**
	 * Create a colinfo using the values from an existing colinfo
	 *
	 * @param first    first col in the colinfo
	 * @param last     last col in the colinfo
	 * @param template template column
	 * @return
	 */
	public Colinfo createColinfo( int first, int last, Colinfo template )
	{
		return createColinfo( first, last, template.getColWidth(), template.getIxfe(), template.getGrbit() );
	}

	@Override
	public int getMaxCol()
	{
		return dimensions.getColLast();
	}

	public Colinfo createColinfo( int first, int last )
	{
		return createColinfo( first, last, Colinfo.DEFAULT_COLWIDTH, 0, 0 );
	}

	/**
	 * return the map of row in this sheet sorted by row #
	 * (will be unsorted if insertions and deletions)
	 *
	 * @return
	 */
	public SortedMap<Integer, Row> getSortedRows()
	{
		SortedMap<Integer, Row> sm = new TreeMap<Integer, Row>( rows );
		return sm;
	}

	/**
	 * return a Map of the Rows
	 */
	public Map<Integer, Row> getRowMap()
	{
		return rows;
	}

	/**
	 * update the INDEX record with the new max Row #
	 * why we need so many redundant references to the Min/Max Row/Cols
	 * is a question for the Redmond sages.
	 */
	@Override
	public void updateDimensions( int row, int c )
	{
		log.trace( "Boundsheet Updating Dimensions: " + row + ":" + col );
		short col = (short) c;
		maximumCellCol = Math.max( maximumCellCol, col );
		maximumCellRow = Math.max( maximumCellRow, row );
		if( dimensions != null )
		{
			dimensions.updateDimensions( row - 1, col );
		}
		if( myidx != null )
		{
			myidx.updateRowDimensions( getMinRow(), getMaxRow() ); // TODO: investigate why no Index is possible
		}
	}

	/**
	 * Add a Value record to a WorkSheet.
	 * This method's purpose is to handle default formatting
	 * of the cell that is being added, and to do any manipulations
	 * neccessary to handle mulrks, mulblanks, etc.  It is also the
	 * main entry point of adding values to the boundsheet.   These values
	 * are then passed into createValrec() which
	 *
	 * @param obj     the value of the new Cell
	 * @param address the address of the new Cell
	 */
	public BiffRec addValue( Object obj, String address, boolean fixNumberAsString )
	{

		// first see if there's an existing item
		int[] rc = ExcelTools.getRowColFromString( address );

		return addValue( obj, rc, fixNumberAsString );

	}

	/**
	 * adds a value to the sheet
	 *
	 * @param obj
	 * @param rc
	 * @return
	 */
	public BiffRec addValue( Object obj, int[] rc, boolean fixNumberAsString )
	{
		return addValue( obj, rc, getWorkBook().getDefaultIxfe(), fixNumberAsString );
	}

	/**
	 * adds a cell to the Sheet
	 *
	 * @param obj
	 * @param rc
	 * @param FORMAT_ID
	 * @return
	 */
	public BiffRec addValue( Object obj, int[] rc, int FORMAT_ID )
	{
		return addValue( obj, rc, FORMAT_ID, false );
	}

	/**
	 * adds a cell to the Sheet
	 *
	 * @param obj
	 * @param rc
	 * @param FORMAT_ID
	 * @param fixNumberAsString - whether to attempt to convert to a number if it is a NSaS situation
	 * @return
	 */
	public BiffRec addValue( Object obj, int[] rc, int FORMAT_ID, boolean fixNumberAsString )
	{
		if( rc[1] > MAXCOLS )
		{
			throw new InvalidRecordException( "Cell Column number: " + rc[1] + " is greater than maximum allowable Columns: " + MAXCOLS );
		}

		// sanity checks
		if( rc[0] > MAXROWS )
		{
			throw new InvalidRecordException( "Cell Row number: " + rc[0] + " is greater than maximum allowable row: " + MAXROWS );
		}

		Row r = getRowByNumber( rc[0] );

		/*
		 * from Doc: The default cell format is always present in an Excel file,
		 * described by the XF record with the fixed index 15 (0-based).
		 *
		 * By default, it uses the worksheet/workbook default cell style,
		 * described by the very first XF record (index 0).
		 */
		if( FORMAT_ID <= 0 )
		{
			FORMAT_ID = getWorkBook().getDefaultIxfe();
		}
		if( FORMAT_ID == getWorkBook().getDefaultIxfe() )
		{
			if( getColInfo( rc[1] ) != null )
			{	/* get default colinfo if possible */
				Colinfo co = getColInfo( rc[1] );
				if( (co != null) && (co.getIxfe() != 0) )
				{
					FORMAT_ID = co.getIxfe();
				}
			}
			if( (r != null) && r.getExplicitFormatSet() )
			{
				FORMAT_ID = r.getIxfe();
			}
		}

		CellRange merge_range = null;

		if( !fastCellAdds )
		{
			try
			{
				BiffRec mycell = getCell( rc[0], rc[1] );
				BiffRec rec = mycell;
				merge_range = rec.getMergeRange();
				// specific cell format overrides any other formats: if (FORMAT_ID == defaultFormatId)
				if( (rec.getIxfe() != getWorkBook().getDefaultIxfe()) && (rec.getIxfe() != 0) )
				{
					FORMAT_ID = rec.getIxfe();
				}
				removeCell( mycell );
			}
			catch( CellNotFoundException cnfe )
			{
				// good!
			}
			;
		}

		// Handle detection of Number stored as Strings
		Object[] fixed = null;
		if( !fastCellAdds && fixNumberAsString && (obj != null) )
		{
			try
			{
				fixed = fixNumberStoredAsString( obj );
				obj = fixed[0];
			}
			catch( Exception e )
			{
				; // not a number!
			}
		}

		XLSRecord rec = createValrec( obj, rc, FORMAT_ID );

		if( !fastCellAdds )
		{ // reapply conditional format and merges
			if( merge_range != null )
			{
				rec.setMergeRange( merge_range );
			}
		}

		// check this does not touch affected cells
		addRecord( rec, rc );

		if( fixed != null )
		{
			FormatHandle f = new FormatHandle( wkbook, getWorkBook().getDefaultIxfe() );
			f.setFormatPattern( fixed[1].toString() );
			rec.setXFRecord( f.getFormatId() );
		}

		rec.resetCacheBytes();

		if( r == null )
		{ // if no row initially, check default row height; if not Excel's default, set row height
			double rh = getDefaultRowHeight();
			if( rh != 12.75 )
			{// the default
				r = getRowByNumber( rc[0] );
				r.setRowHeight( (int) (rh * 20) );
			}
		}
		return rec;
	}

	/**
	 * Returns the *real* last col num.  Unfortunately the dimensions record
	 * cannot be counted on to give a correct value.
	 */
	public int getRealMaxCol()
	{
		return maximumCellCol;
	}

	/**
	 * Add a new colinfo
	 *
	 * @param begCol The beginning column number (0 based)
	 * @param endCol The end column number
	 * @param width  Initial width of the column
	 * @param ixfe   formatting
	 * @param grbit  ??
	 * @return Colinfo
	 */
	public Colinfo addColinfo( int begCol, int endCol, int width, int ixfe, int grbit )
	{
		Colinfo ci = Colinfo.getPrototype( begCol, endCol, width, ixfe );
		ci.setGrbit( grbit );
		ci.setWorkBook( getWorkBook() );
		ci.setSheet( this );
		addColinfo( ci );
		int recpos = getDimensions().getRecordIndex();
		recpos--;
		List sr = getSheetRecs();
		// get to last Colinfo record
		BiffRec rec = (BiffRec) sr.get( recpos );
		// TODO: is it ABSOLUTELY true that if no Colinfos there must be a DefColWidth record????
		while( !(rec instanceof Colinfo) &&
				!(rec instanceof DefColWidth) &&
				(recpos > 0) )
		{ // loop until we find either a colinfo or DEFCOLWIDTH
			rec = (BiffRec) sr.get( --recpos );
		}
		// now position this Colinfo in the proper position within the Colinfo set
		int cf = ci.getColFirst();
		while( (rec instanceof Colinfo) && (((Colinfo) rec).getColFirst() > cf) )
		{
			rec = (BiffRec) sr.get( --recpos );
		}
		recpos++;
		getStreamer().addRecordAt( ci, recpos );
		return ci;
	}

	/**
	 * Moves a cell location from one address to another
	 */
	public void moveCell( String startaddr, String endaddr )
	{
		BiffRec c = getCell( startaddr );
		if( c.getOpcode() == RK )
		{
			try
			{
				Double d = c.getDblVal();
				removeCell( c );
				addValue( d, endaddr );
			}
			catch( Exception e )
			{
				log.info( "Boundsheet.moveCell() error :" + e );
			}
		}
		else
		{
			int[] s = ExcelTools.getRowColFromString( endaddr );
			c.setCol( (short) s[1] );
			c.setRowNumber( s[0] );
			removeCell( startaddr );
			addCell( (CellRec) c );
		}
	}

	/**
	 * Moves a cell location from one address to another,
	 * without any clearing of previous locations.  This is used in sorting
	 * and other cell movements where we do not want to delete from starting address
	 */
	public void updateCellReferences( BiffRec c, String endaddr )
	{
		if( c.getOpcode() == RK )
		{
			try
			{
				Double d = c.getDblVal();
				removeCell( c );
				addValue( d, endaddr );
			}
			catch( Exception e )
			{
				log.info( "Boundsheet.moveCell() error :" + e );
			}
		}
		else
		{
			int[] s = ExcelTools.getRowColFromString( endaddr );
			c.setCol( (short) s[1] );
			c.setRowNumber( s[0] );
			addCell( (CellRec) c );
		}
	}

	/**
	 * Get the print area set for this WorkSheetHandle.
	 * <p/>
	 * If no print area is set return null;
	 */
	public String getPrintArea()
	{
		Name n = getPrintAreaNameRec();
		if( n != null )
		{
			String ret = "";
			Stack s = n.getExpression();
			for( Object value : s )
			{
				Ptg p = (Ptg) value;
/*                    if (p instanceof PtgArea3d) {// can be other than ptgarea ...
                        ((PtgRef)p).clearLocationCache();// why??
                        return p.toString();
                    }*/
				ret += p.toString();
			}
			return ret;
		}
		return null;
	}

	/**
	 * Set the print area for this worksheet.
	 */
	public void setPrintArea( String range )
	{
		setPrintArea( range, Name.PRINT_AREA );
	}

	/**
	 * Get the Print Titles range set for this WorkSheetHandle.
	 * <p/>
	 * If no Print Titles are set, this returns null;
	 */
	public String getPrintTitles()
	{
		Name n = getPrintAreaNameRec( Name.PRINT_TITLES );
		if( n != null )
		{
			Stack s = n.getExpression();
			for( Object value : s )
			{
				Ptg p = (Ptg) value;
				return p.toString();
			}
		}

		return null;
	}

	/**
	 * Set the print titles for this worksheet= row(s) or col(s) to repeat at the top of each page
	 */
	public void setPrintTitles( String range )
	{
		setPrintArea( range, Name.PRINT_TITLES );
	}

	/**
	 * Set the print area or titles for this worksheet.
	 */
	public void setPrintArea( String printarea, byte type )
	{
		if( type != Name.PRINT_TITLES )
		{// can have multiple print title refs
			Name n = getPrintAreaNameRec( type );
			if( n != null )
			{ // TODO: should check if same SHEET -- look at!
				Stack s = n.getExpression();
				for( int x = 0; x < s.size(); x++ )
				{
					Ptg p = (Ptg) s.get( x );
					if( p instanceof PtgRef )
					{
						Ptg ptg = PtgRef.createPtgRefFromString( printarea, n );
						s.remove( x );
						s.add( x, ptg );
					}
				}
				return;
			}
		}
		// create the name
		try
		{
			String t;
			if( type == Name.PRINT_AREA )
			{
				t = "PRINT_AREA";
			}
			else
			{
				t = "PRINT_TITLES";
			}
			Name n = new Name( getWorkBook(), "Built-in: " + t );
			n.setBuiltIn( type );
			int xref = getWorkBook().getExternSheet( true ).insertLocation( getSheetNum(), getSheetNum() );
			n.setExternsheetRef( xref );
			n.updateSheetReferences( this );
			n.setSheet( this );
			n.setIxals( (short) (getSheetNum()) );
			n.setItab( (short) (getSheetNum() + 1) );
			Stack<Ptg> s = new Stack<Ptg>();
			Ptg p = PtgRef.createPtgRefFromString( printarea, n );
			s.push( p );
			n.setExpression( s );
		}
		catch( Exception e )
		{
			log.error( "Error setting print area in boundsheet: " + e );
		}
	}

	/**
	 * returns an arrayList of notes in the worksheet
	 *
	 * @return
	 */
	public ArrayList getNotes()
	{
		ArrayList notes = new ArrayList();
		int idx = getIndexOf( NOTE );
		while( idx > -1 )
		{
			notes.add( SheetRecs.get( idx++ ) );
			if( ((BiffRec) SheetRecs.get( idx )).getOpcode() != NOTE )
			{
				break;
			}
		}
		return notes;
	}

	/**
	 * adds a merged cell to this sheet
	 *
	 * @return
	 */
	public Mergedcells addMergedCellRec()
	{
		Mergedcells mec = (Mergedcells) Mergedcells.getPrototype();
		mec.setSheet( this );
		getStreamer().addRecordAt( mec, getSheetRecs().size() - 1 );
		addMergedCellsRec( mec );
		return mec;
	}

	/**
	 * return existing merged cell records without adding new blank
	 *
	 * @return
	 */
	public List getMergedCells()
	{
		return mc;
	}

	/**
	 * return truth of "has merged cells"
	 *
	 * @return
	 */
	public boolean hasMergedCells()
	{
		return mc.size() > 0;
	}

	/**
	 * get the name of the sheet
	 */
	public String toString()
	{
		return getSheetName();
	}

	/**
	 * clear out object references in prep for closing workbook
	 */
	@Override
	public void close()
	{
		wkbook = null;
		for( Colinfo info : colinfos.values() )
		{
			if( null != info )
			{
				info.close();
			}
		}
		colinfos.clear();

		// TODO : 20140122 : Why not just iterate the values?
		Iterator<Integer> ii = rows.keySet().iterator();
		while( ii.hasNext() )
		{
			Row r = rows.get( ii.next() );
			r.close();
		}
		rows.clear();

		cellsByRow = new TreeMap<>( new CellAddressible.RowMajorComparator() );

		cellsByCol = new CellsByColImpl();
		// TODO: clear recs
		arrayformulas.clear();
		// TODO: clear recs
		transferXfs.clear();
		// TODO: clear recs
		transferFonts.clear();
		imageMap.clear();
		charts.clear();
		ooxmlObjects.clear();
		if( ooxmlShapes != null )
		{
			ooxmlShapes.clear();
		}

		ooautofilter = null;
		mc.clear();
		sheetview = null;    // OOXML sheet view object
		sheetPr = null;        // OOXML sheetPr object
		if( lastselection != null )
		{
			lastselection.close();
			lastselection = null;
		}
		if( protector != null )
		{
			protector.close();
			protector = null;
		}

		if( sheetNameRecs != null )
		{
			ii = sheetNameRecs.keySet().iterator();
			while( ii.hasNext() )
			{
				Name n = (Name) sheetNameRecs.get( ii.next() );
				n.close();
			}
			sheetNameRecs.clear();
		}

		for( int i = 0; i < cond_formats.size(); i++ )
		{
			Condfmt c = (Condfmt) cond_formats.get( i );
			c.close();
		}
		cond_formats.clear();

		for( int i = 0; i < autoFilters.size(); i++ )
		{
			AutoFilter a = autoFilters.get( i );
			a.close();
		}
		autoFilters.clear();

		if( lastCell != null )
		{
			((XLSRecord) lastCell).close();
			lastCell = null;
		}
		if( lastRow != null )
		{
			lastRow.close();
			lastRow = null;
		}

		if( win2 != null )
		{
			win2.close();
			win2 = null;
		}
		if( scl != null )
		{
			scl.close();
			scl = null;
		}
		if( pane != null )
		{
			pane.close();
			pane = null;
		}
		if( dval != null )
		{
			dval.close();
			dval = null;
		}
		if( hdr != null )
		{
			hdr.close();
			hdr = null;
		}
		if( ftr != null )
		{
			ftr.close();
			ftr = null;
		}
		if( wsbool != null )
		{
			wsbool.close();
			wsbool = null;
		}
		if( guts != null )
		{
			guts.close();
			guts = null;
		}
		if( dimensions != null )
		{
			dimensions.close();
			dimensions = null;
		}
		if( mybof != null )
		{
			mybof.close();
			mybof = null;
		}
		if( myeof != null )
		{
			myeof.close();
			myeof = null;
		}
		if( myidx != null )
		{
			myidx.close();
			myidx = null;
		}
		for( Object printRec : printRecs )
		{
			XLSRecord r = (XLSRecord) printRec;
			r.close();
		}
		printRecs.clear();

		// clear out refs by sheet recs
		for( int j = 0; j < SheetRecs.size(); j++ )
		{
			XLSRecord r = (XLSRecord) SheetRecs.get( j );
			r.close();
		}
		SheetRecs.clear();
		if( localrecs != null )
		{
			localrecs.clear();
		}
		// col records

	}

	@Override
	public WorkBook getWorkBook()
	{
		return wkbook;
	}

	/**
	 * initialize the SheetImpl with data from
	 * the byte array.
	 */
	@Override
	public void init()
	{
		super.init();
		int lt = ByteTools.readInt( getByteAt( 0 ), getByteAt( 1 ), getByteAt( 2 ), getByteAt( 3 ) );

		// this is the index used by the BOF's Sheet to associate the record
		lbPlyPos = lt;
		grbit = ByteTools.readShort( getByteAt( 4 ), getByteAt( 5 ) );
		log.trace( "Sheet grbit: " + grbit );
		log.trace( " lbplypos: " + lbPlyPos );
		cch = getByteAt( 6 );
		grbitChr = getByteAt( 7 );
		byte[] namebytes = getBytesAt( 8, getLength() - 12 );
		try
		{
			if( grbitChr == 0x1 )
			{
				sheetname = new String( namebytes, UNICODEENCODING );
			}
			else
			{
				sheetname = new String( namebytes, DEFAULTENCODING );
			}
		}
		catch( UnsupportedEncodingException e )
		{
			log.error( "Boundsheet.init() Unsupported Encoding error: " + e );
		}
		log.debug( "Sheet name: " + sheetname );
		ooxmlObjects = new ArrayList();        // possible that boundsheet is created by readObject and therefore ooxmlObjects will not be set
	}

	/**
	 * do all of the expensive updating here
	 * only right before streaming record.
	 */
	@Override
	public void preStream()
	{
	}

	/**
	 * This seems incorrect, should we not be just returning
	 * charts for the boundsheet in question?
	 *
	 * @return
	 */
	public List<Chart> getCharts()
	{
		return charts;
	}

	/**
	 * add chart to sheet-specific list of charts
	 *
	 * @param c
	 * @see WorkBook.addRecord
	 */
	public void addChart( Chart c )
	{
		charts.add( c );
	}

	/**
	 * Adds a chart from a bytestream, keeps the title intact.
	 *
	 * @param inbytes
	 * @return
	 */
	public Chart addChart( byte[] inbytes, short[] coords )
	{
		return addChart( inbytes, "useDefault", coords );
	}

	/**
	 * populateForTransfer is a method that takes all of the shared resources (SST, XF, Font, etc) records and
	 * verifies that they are populated for use in a destination workbook
	 */
	public void populateForTransfer()
	{
		getSheetHash();
		BiffRec[] recs = getCells();
		for( BiffRec rec : recs )
		{
			if( rec.getOpcode() == LABELSST )
			{
				Labelsst mylabel = (Labelsst) rec;
				mylabel.initUnsharedString();
			}
		}
		transferXfs = getWorkBook().getXfrecs();
		for( Object transferXf : transferXfs )
		{
			Xf x = (Xf) transferXf;
			x.populateForTransfer();
		}

		transferFonts = getWorkBook().getFontRecs();
		for( Object transferFont : transferFonts )
		{
			Font x = (Font) transferFont;
			x.getData();
		}
	}

	/**
	 * @return Returns the lastselection.
	 */
	public Selection getLastselection()
	{
		return lastselection;
	}

	/**
	 * @param lastselection The lastselection to set.
	 */
	public void setLastselection( Selection lastselection )
	{
		this.lastselection = lastselection;
	}

	/**
	 * Set to true to turn off checking for existing cells, conditional formats and merged ranges in order to
	 * accelerate adding new cells
	 *
	 * @param fastCellAdds The fastCellAdds to set.
	 */
	public void setFastCellAdds( boolean fastCellAdds )
	{
		this.fastCellAdds = fastCellAdds;
	}

	/**
	 * scl is for zoom
	 *
	 * @return
	 */
	public Scl getScl()
	{
		if( scl == null )
		{ // we needs one!
			scl = new Scl();
			SheetRecs.add( getIndexOfWindow2(), scl );
			scl.setSheet( this );
		}
		return scl;
	}

	/**
	 * scl is for zoom
	 *
	 * @param scl
	 */
	public void setScl( Scl s )
	{
		scl = s;
	}

	/**
	 * Set whether to shift formula cells inclusively
	 * i.e. if inserting row 5, shift formula D5:D6 down or D6:D7 OR shift inclusive by D5:D7
	 */
	public void setShiftRule( boolean bShiftInclusive )
	{
		formulaShiftInclusive = bShiftInclusive;
	}

	/**
	 * return whether to shift (formula cells, named ranges) "inclusive" or not
	 *
	 * @return
	 */
	public boolean isShiftInclusive()
	{
		return formulaShiftInclusive;
	}

	/**
	 * Gets the printer setup handle for this sheet.
	 */
	public PrinterSettingsHandle getPrinterSetupHandle()
	{
		return new PrinterSettingsHandle( this );
	}

	/**
	 * Gets a list of the print related records for this sheet.
	 *
	 * @return an unmodifiable list of all printing-related records
	 */
	public List<BiffRec> getPrintRecs()
	{
		return Collections.unmodifiableList( printRecs );
	}

	/**
	 * Adds a print-related record to the list of said.
	 */
	public void addPrintRec( BiffRec record )
	{
		if( printRecs == null )
		{
			printRecs = new ArrayList<BiffRec>();
		}
		printRecs.add( record );
	}

	/**
	 * return the AutoFilter record for this Boundsheet, if any
	 * TODO: Merge with OOXML Autofilter
	 *
	 * @return
	 */
	public List getAutoFilters()
	{
		return autoFilters;
	}

	/**
	 * Adds a new Note or Comment to the sheet at the desired address
	 *
	 * @param address - String cell address
	 * @param txt     - Text of Note
	 * @param author  - String Author of Note
	 * @return NoteHandle - a handle to the Note object which allows manipulation
	 */
	public Note createNote( String address, String txt, String author )
	{
		// first check if a note is already attached to this addrss
		ArrayList notes = getNotes();
		if( address.indexOf( '!' ) == -1 )
		{
			address = getSheetName() + "!" + address;
		}
		for( Object note : notes )
		{
			Note n = (Note) note;
			if( n.getCellAddressWithSheet().equals( address ) )
			{
				n.setText( txt );
				n.setAuthor( author );
				return n;
			}
		}

		// add required Mso/object records
		int[] coords = ExcelTools.getRowColFromString( address );
		int insertIndex = insertMSOObjectsForNote( coords );

		// after mso/obj/mso, now add txo/continue/continue and note record
		Txo t = (Txo) Txo.getPrototype();
		t.setSheet( this );
		SheetRecs.add( insertIndex++, t );
		SheetRecs.add( insertIndex++, t.text );    // add the associated Continues that defines the text
		t.text.setPredecessor( t ); // link this continues to it's predecessor
		Continue c = Continue.getBasicFormattingRunContinues();
		c.setPredecessor( t ); // TODO: is this correct????
		c.setSheet( this );
		SheetRecs.add( insertIndex++, c );    // and add associated formatting runs
		try
		{
			t.setStringVal( txt ); // must do *after* adding continues
		}
		catch( IllegalArgumentException e )
		{
			log.error( e.toString() );
		}
		// after (mso/obj/mso/txo/continue/continue) * n, note records are listed in order
		insertIndex = getIndexOf( WINDOW2 );
		Note n = (Note) Note.getPrototype( author );
		n.setId( lastObjId ); // same as Obj record above
		n.setSheet( this );
		n.setRowCol( coords[0], coords[1] );
		SheetRecs.add( insertIndex, n );
		return n;
	}

	/**
	 * Handles the MSO records necessary for defining a DropDown list object
	 *
	 * @return
	 */
	public int insertDropDownBox( int colNum )
	{
		int insertIndex;
		MSODrawingGroup msodg = wkbook.getMSODrawingGroup();
		if( msodg == null )
		{
			msodg = wkbook.createMSODrawingGroup();
			msodg.initNewMSODrawingGroup();
		}

		// insert either above first NOTE record or before WINDOW2 and certain other XLSRECORDS
		insertIndex = getIndexOf( NOTE );
		if( insertIndex == -1 ) // no existing notes - find proper insert index
		{
			insertIndex = getIndexOf( WINDOW2 );
		}
		while( (insertIndex - 1) > 0 )
		{
			short opc = ((BiffRec) SheetRecs.get( insertIndex - 1 )).getOpcode();
			if( (opc == MSODRAWING) || (opc == CONTINUE) )
			{
				MSODrawing rec;
				if( opc == MSODRAWING )
				{
					rec = ((MSODrawing) SheetRecs.get( insertIndex - 1 ));
				}
				else
				{
					rec = ((Continue) SheetRecs.get( insertIndex - 1 )).maskedMso;
					if( rec == null )
					{
						break;
					}
				}
				if( rec.getSOLVERContainerLength() == 0 )
				{
					break;    // solver containers must be last, apparently ... sigh ...
				}
				// else
				// log.info("Boundsheet.InsertMSOObjectsForNote.  SOLVER CONTAINER ENCOUNTED");
			}
			else if( opc == OBJ )
			{
				Obj rec = (Obj) SheetRecs.get( insertIndex - 1 );
				if( rec.getObjType() == Obj.otDropdownlist )        // TODO: verify that drop downs are reused/shared in all cases!!!!
				{
					return rec.getObjId(); // already have one return object id
				}
				break;
			}
			else if( (opc == OBJ) || (opc == CONTINUE) || (opc == DIMENSIONS) || (opc == 0x866) || (opc == 0x1C2) )
			{
				break;
			}
			insertIndex--;
		}

		MSODrawing msoheader = msodg.getMsoHeaderRec( this );
		MSODrawing msoDrawing = (MSODrawing) MSODrawing.getPrototype();
		msoDrawing.setSheet( this );
		msoDrawing.setWorkBook( getWorkBook() );
		if( msoheader == null )
		{
			msoDrawing.setIsHeader();
			msoheader = msoDrawing;
		}

		msoDrawing.createDropDownListStyle( colNum );    // create the records necessary to define the dropdown box symbol at the desired column

		// object record which defines a basic dropdown list
		Obj obj = Obj.getBasicObjRecord( Obj.otDropdownlist, ++lastObjId );    // create a drop-down object record for each
		int objID = obj.getObjId();

		// insert new mso + obj records into sheet
		SheetRecs.add( insertIndex++, msoDrawing );
		SheetRecs.add( insertIndex++, obj );

		// now update msodg + msoheader rec
		msoheader.numShapes++;
		msodg.addMsodrawingrec( msoDrawing );    // add the new drawing rec to the msodrawinggroup set of recs
		wkbook.updateMsodrawingHeaderRec( this );        // find the msodrawing header record and update it (using info from other msodrawing recs)
		msodg.setSpidMax( wkbook.lastSPID + 1 );
		msodg.updateRecord();        // given all information, generate appropriate bytes
		msodg.dirtyflag = true;

		return objID;
	}

	/**
	 * Adds a new Note or Comment to the sheet at the desired address
	 * with Formatting (Font) information
	 *
	 * @param address - String cell address
	 * @param txt     - Unicode string reprentation of the note, including formatting
	 * @param author  - String Author of Note
	 * @return NoteHandle - a handle to the Note object which allows manipulation
	 */
	public Note createNote( String address, Unicodestring txt, String author )
	{
		Note nh = createNote( address, txt.getStringVal(), author );
		// TODO: deal with formats - incorporate into Txo/Continues -- for now they are just stored as-is, no modification allowed
		nh.setFormattingRuns( txt.getFormattingRuns() );
		return nh;
	}

	/**
	 * removes the desired note from the sheet
	 *
	 * @param n
	 */
	public void removeNote( Note n )
	{
		int id = n.getId();
		int idx = getIndexOf( OBJ );
		if( idx == -1 )
		{
			return;    // should't!
		}
		while( idx < SheetRecs.size() )
		{
			if( ((BiffRec) SheetRecs.get( idx )).getOpcode() == OBJ )
			{
				Obj o = ((Obj) SheetRecs.get( idx ));
				// if it's of type Note + has the same id, this is it
				if( (o.getObjType() == 0x19) && (o.getObjId() == id) )
				{ // got it!
					// apparently sometimes you don't find the mso/obj/mso combo, so check
					if( ((BiffRec) SheetRecs.get( idx - 1 )).getOpcode() == MSODRAWING )
					{
						idx--;
						break;
					}
					if( (((BiffRec) SheetRecs.get( idx + 1 )).getOpcode() == CONTINUE) && ((((Continue) SheetRecs.get( idx + 1 ))).maskedMso != null) )
					{
						// idx++;
						break;
					}
				}
			}
			idx++;
		}
		// usual format= mso/obj/mso/txo/continue/continue but can also be:
		// obj/continue (mso)/txo/continue/continue/continue (mso)
		int objidx = 0;
		int msoidx = 0;
		boolean maskedMso = true; // handle continues masking mso's
		while( idx < SheetRecs.size() )
		{
			MSODrawing mso = null;
			BiffRec rec = (BiffRec) SheetRecs.get( idx );
			if( rec.getOpcode() == OBJ )
			{
				objidx++;
			}
			else if( rec.getOpcode() == MSODRAWING )
			{
				mso = (MSODrawing) rec;
				maskedMso = false;
				if( mso.getShapeType() == MSODrawingConstants.msosptTextBox )
				{
					msoidx++;
				}
				else if( (((MSODrawing) rec).isShape) )    // it's not a text box or the associated text "oddball" mso, so break (Another test: SPID==0??)
				{
					break;
				}
			}
			else if( (rec.getOpcode() == CONTINUE) && maskedMso )
			{
				mso = ((Continue) rec).maskedMso;
				if( mso.getShapeType() == MSODrawingConstants.msosptTextBox )
				{
					msoidx++;
				}
				else if( (((MSODrawing) rec).isShape) )    // it's not a text box or the associated text "oddball" mso, so break (Another test: SPID==0??)
				{
					break;
				}
			}
			else if( rec.getOpcode() == NOTE )
			{
				break;
			}
			if( (objidx > 1) || (msoidx > 1) )    // reached the next set of note-associated recs, so get out
			{
				break;
			}
			SheetRecs.remove( idx ); // otherwise, ok to delete
			if( (mso != null) && mso.isShape )
			{// if removed an mso, must update msodg
				MSODrawingGroup msodg = wkbook.getMSODrawingGroup();
				msodg.removeMsodrawingrec( mso, this, true );
			}
		}
		// now remove the actual note record
		idx = getIndexOf( NOTE );
		while( (idx < SheetRecs.size()) && (((BiffRec) SheetRecs.get( idx )).getOpcode() == NOTE) )
		{
			if( SheetRecs.get( idx ).equals( n ) )
			{
				SheetRecs.remove( idx );
				break; // we're done
			}
			idx++;
		}
	}

	/**
	 * Adds a new AutoFilter to the specified column
	 *
	 * @param int column - 0-based column number
	 * @return AutoFilterHandle Handle to the new AutoFilter
	 */
	public AutoFilter addAutoFilter( int column )
	{
		// if there are no existing AutoFilters on the sheet,
		// then must add a mso/obj pair for each column on the sheet
		// to define dropdown box next to each column
		// also must add built-in name _FILTERDATABASE +
		// must add a mystery XlSRecord with opcode== 0x9D -- cannot find any information about this opcode
		if( (autoFilters == null) || (autoFilters.size() == 0) )
		{
			// add _FILTERDATABASE Name
			addFilterDatabase();

			/* 20100216 KSC: WHAT ARE  THESE RECORD??? They are necessary for new AutoFilter's */
			int zz = getIndexOf( COLINFO );
			if( zz == -1 )
			{
				zz = getIndexOf( DEFCOLWIDTH ) + 1;
			}
			else
			{
				while( ((BiffRec) SheetRecs.get( zz )).getOpcode() == COLINFO )
				{
					zz++;
				}
			}
			// insert after COLINFOs or DefColWidth
			XLSRecord rec = new XLSRecord();
			rec.setOpcode( (short) 155 ); // no data for this record
			rec.setData( new byte[]{ } );
			SheetRecs.add( zz++, rec );
			rec = new XLSRecord();
			rec.setOpcode( (short) 157 );    // this has SOMETHING to do with # columns ...
			rec.setData( new byte[]{ (byte) getMaxCol(), 0 } );
			SheetRecs.add( zz, rec );

			// add required Mso/object records
			int insertIndex;
			MSODrawingGroup msodg = wkbook.getMSODrawingGroup();
			if( msodg != null )
			{ // already have drawing records; just add to records + update msodg
				insertIndex = getIndexOf( MSODRAWINGSELECTION );
				if( insertIndex < 0 )
				{
					insertIndex = getIndexOf( WINDOW2 );
				}
			}
			else
			{ // No images present in workbook, must add appropriate records
				msodg = wkbook.createMSODrawingGroup();
				msodg.initNewMSODrawingGroup();    // generate and add required records for drawing records
				// insertion point for new msodrawing rec
				insertIndex = getIndexOf( DIMENSIONS ) + 1;
			}

			MSODrawing msoheader = msodg.getMsoHeaderRec( this );

			// Must add for each column
			for( int i = 0; i < getRealMaxCol(); i++ )
			{
				try
				{
					if( getCellsByCol( i ).size() == 0 )
					{
						break;
					}
				}
				catch( CellNotFoundException e )
				{
					break;
				}
				// Colinfo ci= (Colinfo) this.colinfos.get(i);
				// short j= (short) ci.getColFirst(); // column number'
				short j = (short) i;
				MSODrawing msoDrawing = (MSODrawing) MSODrawing.getPrototype();
				msoDrawing.setWorkBook( wkbook );
				msoDrawing.setSheet( this );
				if( msoheader == null )
				{
					msoDrawing.setIsHeader();
					msoheader = msoDrawing;
				}

				msoDrawing.createDropDownListStyle( j );    // create the records necessary to define the dropdown box symbol

				// object record which defines a basic dropdown list
				Obj obj = Obj.getBasicObjRecord( Obj.otDropdownlist, ++lastObjId );    // create a drop-down object record for each

				// insert new mso + obj records into sheet
				SheetRecs.add( insertIndex++, msoDrawing );
				SheetRecs.add( insertIndex++, obj );

				// now update msodg + msoheader rec
				msoheader.numShapes++;
				msodg.addMsodrawingrec( msoDrawing );    // add the new drawing rec to the msodrawinggroup set of recs
				wkbook.updateMsodrawingHeaderRec( this );        // find the msodrawing header record and update it (using info from other msodrawing recs)
				msodg.setSpidMax( wkbook.lastSPID + 1 );
				msodg.updateRecord();        // given all information, generate appropriate bytes
				msodg.dirtyflag = true;
			}
		}

		AutoFilter af = (AutoFilter) AutoFilter.getPrototype();
		af.setSheet( this );
		af.setCol( column );
		int i = getIndexOf( DIMENSIONS ); // insert just before DIMENSIONS record
		SheetRecs.add( i, af );

		autoFilters.add( af );
		return af;
	}

	/**
	 * removes all autofilters from this sheet
	 */
	public void removeAutoFilter()
	{
		removeFilterDatabase();    // remove the _FILTER_DATABASE name necessary for AutoFilters
		int zz = getIndexOf( AUTOFILTER ); // remove all AutoFitler records
		while( zz != -1 )
		{
			SheetRecs.remove( zz );
			zz = getIndexOf( AUTOFILTER );
		}
		// remove the two unknown records
		zz = getIndexOf( (short) 155 );
		if( zz > -1 )
		{
			SheetRecs.remove( zz );
		}
		zz = getIndexOf( (short) 157 );
		if( zz > -1 )
		{
			SheetRecs.remove( zz );
		}
		// and hows about the Mso/Obj records, huh? huh?
		autoFilters.clear();
		// finally, must set all rows to NOT hidden - I believe Excel does this when AutoFilters are turned off
		for( int i = 0; i < rows.size(); i++ )
		{
			rows.get( i ).setHidden( false );
		}
	}

	/**
	 * adds a sxview - pivot table lead record - and required associated records to the worksheet
	 * <br>other methods that add data, row, col and page fields will fill in the pivot table fields and formatting info
	 *
	 * @param ref Cell Range which identifies pivot table data range
	 * @param wbh WorkBookHandle
	 * @param sId Stream or cachid Id -- links back to SxStream set of records
	 * @return
	 */
	public Sxview addPivotTable( String ref, WorkBookHandle wbh, int sId, String tablename )
	{
		wkbook.addPivotCache( ref, wbh, sId );    // create the directory/storage for a pivot cache, if not already created
		// ensure the proper directory/storage and pivot cache record is created
		int zz = win2.getRecordIndex() - 1;
		while( zz > 0 )
		{
			if( ((BiffRec) SheetRecs.get( zz )).getOpcode() == NOTE )
			{
				break;
			}
			if( ((BiffRec) SheetRecs.get( zz )).getOpcode() == OBJ )
			{
				break;
			}
			if( ((BiffRec) SheetRecs.get( zz )).getOpcode() == DIMENSIONS )
			{
				break;
			}
			zz--;
		}
		zz++;
		// minimal configuration
		Sxview sx = (Sxview) Sxview.getPrototype();
		SheetRecs.add( zz++, sx );
		SheetRecs.addAll( zz, sx.addInitialRecords( this ) );
		sx.setTableName( tablename );
		wkbook.addPivotTable( sx );    // add to lookup
		return sx;
	}

	/**
	 * update row filter (hidden status) by evaluating AutoFilter conditions on the sheet
	 * <br>Must do after autofilter updates or additions
	 */
	public void evaluateAutoFilters()
	{
		// first must set all rows to NOT hidden
		for( int i = 0; i < rows.size(); i++ )
		{
			try
			{
				rows.get( i ).setHidden( false );
			}
			catch( NullPointerException e )
			{
				// blank rows ...
			}
		}

		// now evaluate all autofilters
		for( int i = 0; i < autoFilters.size(); i++ )
		{
			(autoFilters.get( i )).evaluate();
		}
	}

	/**
	 * returns the list of Excel 2007 objects which are external or auxillary to this sheet
	 * e.g printerSeettings, vmlDrawings
	 *
	 * @return
	 */
	public List getOOXMLObjects()
	{
		return ooxmlObjects;
	}

	/**
	 * adds the object-specific signature of the external or auxillary Excel 2007 object
	 * e.g. oleObjects, vmlDrawings
	 *
	 * @param o
	 */
	public void addOOXMLObject( Object o )
	{
		ooxmlObjects.add( o );
	}

	/**
	 * set if row has thick bottom by default (Excel 2007-Specific)
	 */
	public boolean hasThickBottom()
	{
		return thickBottom;
	}

	/**
	 * return true if row has thick top by default (Excel 2007-Specific)
	 */
	public boolean hasThickTop()
	{
		return thickTop;
	}

	/**
	 * return true if rows are hidden by default (Excel 2007-Specific)
	 */
	public boolean hasZeroHeight()
	{
		return zeroHeight;
	}

	/**
	 * return true if defaultrowheight is manually set (Excel 2007-Specific)
	 */
	public boolean hasCustomHeight()
	{
		return customHeight;
	}

	/**
	 * return the default row height in points (Excel 2007-Specific)
	 */
	public double getDefaultRowHeight()
	{
		return defaultRowHeight;
	}

	/**
	 * set the default row height in points (Excel 2007-Specific)
	 */
	public void setDefaultRowHeight( double h )
	{
		defaultRowHeight = h;
	}

	/**
	 * return the default column width in # characters of the maximum digit width of the normal style's font
	 * <p/>
	 * This is currently a floating point value, something I question.  I don't understand the need for this,
	 * and possibly it should be an int?
	 */
	public float getDefaultColumnWidth()
	{
		// biff8 setting
		if( defColWidth != null )
		{
			return defColWidth.getDefaultWidth();
		}
		return defaultColWidth;
	}

	/**
	 * set the default column width in # characters of the maximum digit width of the normal style's font
	 */
	public void setDefaultColumnWidth( float w )
	{
		// ooxml setting
		defaultColWidth = w;
		// biff8 setting
		if( defColWidth != null )
		{
			defColWidth.setDefaultColWidth( (int) w );
		}
	}

	/**
	 * set if row has thick bottom by default (Excel 2007-Specific)
	 */
	public void setThickBottom( boolean b )
	{
		thickBottom = b;
	}

	/**
	 * set if row has thick top by default (Excel 2007-Specific)
	 */
	public void setThickTop( boolean b )
	{
		thickTop = b;
	}

	/**
	 * set if rows are hidden by default (Excel 2007-Specific)
	 */
	public void setZeroHeight( boolean b )
	{
		zeroHeight = b;
	}

	/**
	 * set if defaultrowheight is manually set (Excel 2007-Specific)
	 */
	public void setHasCustomHeight( boolean b )
	{
		customHeight = b;
	}

	/**
	 * store Excel 2007 shape via Shape Name
	 *
	 * @param tca
	 */
	public void addOOXMLShape( TwoCellAnchor tca )
	{
		if( ooxmlShapes == null )
		{
			ooxmlShapes = new HashMap();
		}
		ooxmlShapes.put( tca.getName(), tca );
	}

	/**
	 * store Excel 2007 shape via Shape Name
	 *
	 * @param tca
	 */
	public void addOOXMLShape( OneCellAnchor oca )
	{
		if( ooxmlShapes == null )
		{
			ooxmlShapes = new HashMap();
		}
		ooxmlShapes.put( oca.getName(), oca );
	}

	/**
	 * Store Excel 2007 legacy drawing shapes
	 *
	 * @param vml
	 */
	public void addOOXMLShape( Object vml )
	{
		if( ooxmlShapes == null )
		{
			ooxmlShapes = new HashMap();
		}
		ooxmlShapes.put( "vml", vml );    // only 1 vml (=legacy drawing info) per sheet so just refer to it as "vml"
	}

	/**
	 * return map of Excel 2007 shapes in this workbook
	 *
	 * @return
	 */
	public HashMap getOOXMLShapes()
	{
		return ooxmlShapes;
	}

	/**
	 * returns the Excel 2007 sheetView element for this sheet (controls topLeftCell, pane attributes ...
	 *
	 * @return
	 */
	public SheetView getSheetView()
	{
		return sheetview;
	}

	/**
	 * set the Excel 2007 sheetView element for this sheet (controls topLeftCell, pane attributes ...
	 *
	 * @param s
	 */
	public void setSheetView( SheetView s )
	{
		sheetview = s;
	}

	/**
	 * returns the Excel 2007 sheetPr sheet Properties element for this sheet (controls codename, tabColor ...)
	 *
	 * @return
	 */
	public SheetPr getSheetPr()
	{
		return sheetPr;
	}

	/**
	 * set the Excel 2007 sheetView element for this sheet (controls topLeftCell, pane attributes ...
	 *
	 * @param s
	 */
	public void setSheetPr( SheetPr s )
	{
		sheetPr = s;
	}

	/**
	 * returns the Excel 2007 autoFilter element for this sheet (temporarily hides rows based upon filter criteria)
	 * TODO: Merge with 2003 AutoFilter
	 *
	 * @return
	 */
	public org.openxls.formats.OOXML.AutoFilter getOOAutoFilter()
	{
		return ooautofilter;
	}

	/**
	 * set the Excel 2007 autoFilter element for this sheet (temporarily hides rows based upon filter criteria)
	 * TODO: Merge with 2003 AutoFilter
	 *
	 * @param strref
	 */
	public void setOOAutoFilter( org.openxls.formats.OOXML.AutoFilter a )
	{
		ooautofilter = a;
	}

	/**
	 * returns a scoped named range by name string
	 *
	 * @param t
	 * @return
	 */
	public Name getScopedName( String nameRef )
	{
		Object o = sheetNameRecs.get( nameRef.toUpperCase() );    // case insensitive
		if( o == null )
		{
			return null;
		}
		return (Name) o;
	}

	/**
	 * Add a sheet-scoped name record to the boundsheet
	 * <p/>
	 * Note this is not that primary repository for names, it just contains the name records
	 * that are bound to this sheet, adding them here will not add them to the workbook;
	 *
	 * @param sheetNameRecs
	 */
	public void addLocalName( Name name )
	{
		if( sheetNameRecs == null )
		{
			sheetNameRecs = new HashMap();
		}
		sheetNameRecs.put( name.getNameA(), name );
	}

	/**
	 * Remove a sheet-scoped name record from the boundsheet.
	 * <p/>
	 * Note this is not that primary repository for names, it just contains the name records
	 * that are bound to this sheet, removing them here will not remove them completely from the workbook.
	 * <p/>
	 * In order to do that you will need to call book.removeName
	 *
	 * @param sheetNameRecs
	 */
	public void removeLocalName( Name name )
	{
		sheetNameRecs.remove( name.getNameA() );
	}

	/**
	 * Get a sheet scoped name record from the boundsheet
	 *
	 * @return
	 */
	public Name getName( String name )
	{
		if( sheetNameRecs == null )
		{
			return null;
		}
		Object o = sheetNameRecs.get( name.toUpperCase() ); // case insensitive
		if( o != null )
		{
			return (Name) o;
		}
		return null;
	}

	/**
	 * Get all the names for this boundsheet
	 *
	 * @return
	 */
	public Name[] getAllNames()
	{
		if( sheetNameRecs == null )
		{
			sheetNameRecs = new HashMap();
		}
		ArrayList retnames = new ArrayList( sheetNameRecs.values() );
		Name[] names = new Name[retnames.size()];
		return (Name[]) retnames.toArray( names );
	}

	/**
	 * add pritner setting record to worksheet recs
	 *
	 * @param r printer setting record (Margins, PLS)
	 */
	public void addMarginRecord( BiffRec r )
	{
		r.setSheet( this );
		int i = getIndexOf( SETUP );
		int thisOpCode = r.getOpcode();
		// iterate up from SETUP record
		// desired order:
		// WsBool, HeaderRec, FooterRec, HCenter, VCenter, LeftMargin, RightMargin, TopMargin, BottomMargin, Pls
		while( i > 0 )
		{
			int prevOpCode = ((BiffRec) getSheetRecs().get( --i )).getOpcode();
			if( (prevOpCode == VCENTER) || (prevOpCode == FOOTERREC) ||
					(prevOpCode == LEFT_MARGIN) )
			{
				break;
			}
			if( ((prevOpCode == BOTTOM_MARGIN) || (prevOpCode == TOP_MARGIN) || (prevOpCode == RIGHT_MARGIN)) && (thisOpCode == PLS) )
			{
				break;
			}
			if( ((prevOpCode == TOP_MARGIN) || (prevOpCode == RIGHT_MARGIN)) && (thisOpCode == BOTTOM_MARGIN) )
			{
				break;
			}
			if( (prevOpCode == RIGHT_MARGIN) && (thisOpCode == TOP_MARGIN) )
			{
				break;
			}
		}
		SheetRecs.add( ++i, r );
	}

	public DefColWidth getDefColWidth()
	{
		return defColWidth;
	}

	public void setDefColWidth( DefColWidth defColWidth )
	{
		this.defColWidth = defColWidth;
	}

	/**
	 * Get the print area or titles name rec for this
	 * boundsheet, return null if not exists
	 *
	 * @return
	 */
	protected Name getPrintAreaNameRec( byte type )
	{
		ArrayList<Name> names = getBuiltInNames();
		for( Object name : names )
		{
			Name n = (Name) name;
			if( n.getBuiltInType() == type )
			{
				return n;
			}
		}
		return null;
	}

	/**
	 * Get the print area name rec for this
	 * boundsheet, return null if not exists
	 *
	 * @return
	 */
	protected Name getPrintAreaNameRec()
	{
		return getPrintAreaNameRec( Name.PRINT_AREA );
	}

	/**
	 * return local XF records, used for boundsheet transferral.
	 */
	protected List getTransferXfs()
	{
		return transferXfs;
	}

	/**
	 * return local Font records, used for boundsheet transferral.
	 */
	protected List getTransferFonts()
	{
		return transferFonts;
	}

	/**
	 * parses OOXML content files given a content list cl from zip file zip
	 * recurses if content file has it's own content
	 * *************************************
	 * NOTE: certain elements we do not as of yet process; we "pass-through" or store such elements along with any embedded objects associated with them
	 * for example, activeX objects, vbaProject.bin, etc.
	 * *************************************
	 *
	 * @param bk        WorkBookHandle
	 * @param sheet     WorkSheetHandle (set if recursing)
	 * @param zip       currently open ZipOutputStream
	 * @param cl        ArrayList of Contents (type, filename, rId) to parse
	 * @param parentDir Parent Directory for relative paths in content lists
	 * @param formulas, hyperlinks, inlineStrs -- ArrayLists/Hashmaps stores sheet-specific info for later entry
	 * @throws CellNotFoundException
	 * @throws XmlPullParserException
	 */
	protected void parseSheetElements( WorkBookHandle bk,
	                                   ZipFile zip,
	                                   ArrayList cl,
	                                   String parentDir,
	                                   String externalDir,
	                                   ArrayList formulas,
	                                   ArrayList hyperlinks,
	                                   HashMap inlineStrs,
	                                   HashMap<String, WorkSheetHandle> pivotTables ) throws XmlPullParserException, CellNotFoundException
	{
		String p;
		ZipEntry target;

		try
		{
			for( Object aCl : cl )
			{
				String[] c = (String[]) aCl;
				String ooxmlElement = c[0];

				//if(DEBUG)
				//  log.info("OOXMLReader.parse: " + ooxmlElement + ":" + c[1] + ":" + c[2]);

				p = StringTool.getPath( c[1] );
				p = OOXMLReader.parsePathForZip( p, parentDir );
				if( !ooxmlElement.equals( "hyperlink" ) )  // if it's a hyperlink reference, don't strip path info :)
				{
					c[1] = StringTool.stripPath( c[1] );
				}
				String f = c[1];
				String rId = c[2];

				if( ooxmlElement.equals( "drawing" ) )
				{ // images, charts
					// parse drawing rels to obtain image file names and chart xml files
					target = OOXMLReader.getEntry( zip, p + "_rels/" + f.substring( f.lastIndexOf( "/" ) + 1 ) + ".rels" );
					ArrayList drawingFiles = null;
					if( target != null )    // first retrieve enbedded content in .rels (images, charts ...)
					{
						drawingFiles = OOXMLReader.parseRels( OOXMLReader.wrapInputStream( OOXMLReader.wrapInputStream( zip.getInputStream(
								target ) ) ) ); // obtain a list of image file references for use in later parsing
					}
					target = OOXMLReader.getEntry( zip, p + f );  // now get drawingml file and process it
					parseDrawingXML( bk, drawingFiles, OOXMLReader.wrapInputStream( zip.getInputStream( target ) ), zip, p, externalDir );
				}
				else if( ooxmlElement.equals( "vmldrawing" ) )
				{ // legacy drawing elements
					target = OOXMLReader.getEntry( zip, p + f );
					StringBuffer vml = parseLegacyDrawingXML( bk, OOXMLReader.wrapInputStream( zip.getInputStream( target ) ) );
					target = OOXMLReader.getEntry( zip, p + "_rels/"      // get external objects linked to the vml by parsing it's rels
							+ f.substring( f.lastIndexOf( "/" ) + 1 ) + ".rels" );
					if( target != null )
					{
						String[] embeds = OOXMLReader.storeEmbeds( zip, target, p, externalDir );   // passes thru embedded objects
						addOOXMLShape( new Object[]{ vml, embeds } );
					}
					else
					{
						addOOXMLShape( vml );
					}
	            /**/
				}
				else if( ooxmlElement.equals( "hyperlink" ) )
				{      // hyperlinks
					c = (String[]) aCl;    // don't strip path
					for( Object hyperlink1 : hyperlinks )
					{
						if( rId.equals( ((String[]) hyperlink1)[0] ) )
						{
							String[] h = (String[]) hyperlink1;
							try
							{    // target= cl[2], ref= h[1], desc= h[2]
								bk.getWorkSheet( getSheetName() ).getCell( h[1] ).setURL( rId, h[2], "" );  // TODO: hyperlink text mark
							}
							catch( Exception e )
							{
								log.error( "OOXMLAdapter.parse: failed setting hyperlink to cell " + h[1] + ":" + e.toString() );
							}
							break;
						}
					}
				}
				else if( OOXMLReader.parsePivotTables && ooxmlElement.equals( "pivotTable" ) )
				{    // sheet-parent
/*
 * TODO: Do we really need to get rels ????
                	// must lookup cacheid from rid of pivotCacheDefinitionX.xml in pivotTableDefinitionX.xml.rels
                    target= OOXMLReader.getEntry(zip,p + "_rels/" + f.substring(f.lastIndexOf("/")+1)+".rels");
 * 					ArrayList ptrels= parseRels(wrapInputStream(wrapInputStream(zip.getInputStream(target))));
                	if (ptrels.size() > 1) {	// what could this be?
                		log.warn("OOXMLReader.parse: Unknown Pivot Table Association: " + ptrels.get(1));
                	}
                	String pcd= ((String[])ptrels.get(0))[1];
                	pcd= pcd.substring(pcd.lastIndexOf("/")+1);
                	Object cacheid= null;
                    for (int z= 0; z < pivotCaches.size(); z++) {
                		Object[] o= (Object[]) pivotCaches.get(z);
                		if (pcd.equals(o[0])) {
                			cacheid= o[1];
                			break;
                		}
                    }

                	target = getEntry(zip,p + f);
                	PivotTableDefinition.parseOOXML(bk, /*cacheid, * /this, wrapInputStream(zip.getInputStream(target)));*/
					try
					{    // SAVE FOR LATER INPUT -- must do after all sheets are input ...
						pivotTables.put( p + f, bk.getWorkSheet( getSheetName() ) );
					}
					catch( WorkSheetNotFoundException we )
					{
					}

				}
				else if( ooxmlElement.equals( "comments" ) )
				{   // parse comments or notes
					target = OOXMLReader.getEntry( zip, p + f );
					parseCommentsXML( bk, OOXMLReader.wrapInputStream( zip.getInputStream( target ) ) );

					// Below are elements we do not as of yet handle
				}
				else if( ooxmlElement.equals( "macro" ) || ooxmlElement.equals( "activeX" ) || ooxmlElement.equals( "table" ) || ooxmlElement
						.equals( "vdependencies" ) || ooxmlElement.equals( "oleObject" ) || ooxmlElement.equals( "image" ) || ooxmlElement.equals(
						"printerSettings" ) )
				{

					String attrs = "";
					if( (shExternalLinkInfo != null) && (shExternalLinkInfo.get( rId ) != null) )
					{
						attrs = shExternalLinkInfo.get( rId );
					}
					OOXMLReader.handleSheetPassThroughs( zip, bk, this, p, externalDir, c, attrs );
//                	OOXMLReader.handlePassThroughs(zip, bk, this, p, c);   // pass-through this file and any embedded objects as well
				}
				else
				{    // unknown type
					log.warn( "OOXMLAdapter.parse:  XLSX Option Not yet Implemented " + ooxmlElement );
				}
			}
		}
		catch( IOException e )
		{
			log.error( "OOXMLAdapter.parse failed: " + e.toString() );
		}
		shExternalLinkInfo = null;
	}

	/**
	 * Rationalizes the itab (sheet reference) for name records,
	 * this has to occur after sheet insert/delete operations to keep the
	 * references intact.  Unfortunately these references do not use the Externsheet,
	 * so are not ilbl listeners.
	 */
	void updateLocalNameReferences()
	{
		if( sheetNameRecs == null )
		{
			return;
		}
		Iterator i = sheetNameRecs.values().iterator();
		while( i.hasNext() )
		{
			Name n = (Name) i.next();
			n.setItab( (short) (getSheetNum() + 1) );
		}
	}

	/**
	 * @param fid
	 * @return
	 */
	int translateFontIndex( int fid, HashMap localFonts )
	{
		if( (transferFonts != null) && ((fid - 1) < transferFonts.size()) )
		{
			// must translate fid to corrent font index for current fonts
			// translate font style and see if already present
			Font thisFont = (Font) transferFonts.get( fid - 1 );
			String xmlFont = "<FONT><" + thisFont.getXML() + "/></FONT>";
			Object fontNum = localFonts.get( xmlFont );
			if( fontNum != null )
			{ // then get the fontnum in this book
				fid = (Integer) fontNum;
			}
			else
			{ // it's a new font for this workbook, add it in
				fid = getWorkBook().insertFont( thisFont ) + 1;
				localFonts.put( xmlFont, fid );
			}
		}
		if( fid > getWorkBook().getNumFonts() )
		{    // if fid is still incorrect, set to 0
			fid = 0;
		}
		return fid;
	}

	void parseOOXML( WorkBookHandle bk,
	                 WorkSheetHandle sheet,
	                 InputStream ii,
	                 ArrayList sst,
	                 ArrayList formulas,
	                 ArrayList hyperlinks,
	                 HashMap inlineStrs ) throws XmlPullParserException, IOException
	{
		int sfindex = formulas.size();

//        try {
		Row r = null;
		String cellAddr = null;
		int formatId = 0;
		String type = "";
		shExternalLinkInfo = new HashMap<>();

		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware( true );
		XmlPullParser xpp = factory.newPullParser();

		xpp.setInput( ii, null ); // using XML 1.0 specification
		int eventType = xpp.getEventType();
		while( eventType != XmlPullParser.END_DOCUMENT )
		{
			if( eventType == XmlPullParser.START_TAG )
			{
				String tnm = xpp.getName();
				if( tnm.equals( "sheetFormatPr" ) )
				{ // baseColWidth, customHeight (true if defaultRowHeight has been manually set),
					// defaultColWidth, defaultRowHeight - optimiztion so that we don't have to write out values on each
					// thickBottom  - true if rows have a thick bottom by default
					// thickTop     - true if rows have a thick top by default
					// zeroHeight   - true if rows are hidden by default (an optimization)
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{
						String n = xpp.getAttributeName( i );
						String v = xpp.getAttributeValue( i );
						if( n.equals( "thickBottom" ) )
						{
							setThickBottom( true );
						}
						else if( n.equals( "thickTop" ) )
						{
							setThickTop( true );
						}
						else if( n.equals( "zeroHeight" ) )
						{
							setZeroHeight( v.equals( "1" ) );
						}
						else if( n.equals( "customHeight" ) )
						{
							setHasCustomHeight( v.equals( "1" ) );
						}
						else if( n.equals( "defaultColWidth" ) )
						{
							setDefaultColumnWidth( new Float( v ) );
						}
						else if( n.equals( "defaultRowHeight" ) )
						{
							setDefaultRowHeight( new Double( v ) );
						}
					}
				}
				else if( tnm.equals( "sheetView" ) )
				{      // TODO: finish handling options
					SheetView s = (SheetView) SheetView.parseOOXML( xpp ).cloneElement();
					setSheetView( s );
					getWindow2().setShowGridlines( !(s.getAttrS( "showGridlines" )).equals( "0" ) );
					if( s.getAttr( "showRowColHeaders" ) != null )
					{
						getWindow2().setShowSheetHeaders( (s.getAttrS( "showRowColHeaders" )).equals( "1" ) );
					}
					if( s.getAttr( "showZeros" ) != null )
					{
						getWindow2().setShowZeroValues( (s.getAttrS( "showZeros" )).equals( "1" ) );
					}
					if( s.getAttr( "showOutlineSymbols" ) != null )
					{
						getWindow2().setShowOutlineSymbols( (s.getAttrS( "showOutlineSymbols" )).equals( "1" ) );
					}
					if( s.getAttr( "tabSelected" ) != null )
					{
						setSelected( (s.getAttrS( "tabSelected" )).equals( "1" ) );
					}
					if( s.getAttr( "zoomScale" ) != null )
					{
						getScl().setZoom( new Double( s.getAttrS( "zoomScale" ) ).floatValue() / 100 );
					}
				}
				else if( tnm.equals( "sheetPr" ) )
				{    // sheet properties element
					SheetPr sp = (SheetPr) SheetPr.parseOOXML( xpp ).cloneElement();
					setSheetPr( sp );
				}
				else if( tnm.equals( "dimension" ) )
				{  // ref attribute
                        /* this may not reflect actual rows/cols in sheet
                         * just let our normal machinery set the sheet dimensions
                         String ref= xpp.getAttributeValue(0);
                         int[] rc= ExcelTools.getRangeCoords(ref);
                         this.updateDimensions(rc[2]-1, rc[3]);
*/
				}
				else if( tnm.equals( "sheetProtection" ) )
				{  // ref attribute
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{
						String nm = xpp.getAttributeName( i );
						String v = xpp.getAttributeValue( i );
						if( nm.equals( "password" ) )
						{
							getProtectionManager().setPasswordHashed( v );
						}
						else if( nm.equals( "sheet" ) )
						{
							getProtectionManager().setProtected( OOXMLReader.parseBoolean( v ) );
						}
					}
				}
				else if( tnm.equals( "col" ) )
				{    // min, max, width
					int min = 0, max = 0, style = 0;
					double width = 0;
					boolean hidden = false;
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{
						String nm = xpp.getAttributeName( i );
						String v = xpp.getAttributeValue( i );
						if( nm.equals( "min" ) )
						{
							min = Integer.valueOf( v );
						}
						else if( nm.equals( "max" ) )
						{
							max = Integer.valueOf( v );
						}
						else if( nm.equals( "width" ) )
						{
							width = new Double( v );
						}
						else if( nm.equals( "hidden" ) )
						{
							hidden = true;
						}
						else if( nm.equals( "style" ) )    // customFormat?
						{
							style = Integer.valueOf( v );
						}
					}
					if( max > MAXCOLS )
					{
						max = MAXCOLS - 1;
					}
					ColHandle col = sheet.addCol( min - 1, max - 1 );
					col.setWidth( (int) (width * OOXMLReader.colWFactor) );
					if( style > 0 )
					{
						col.setFormatId( style );
					}
					//col.setColLast(max-1);
					if( hidden )
					{
						col.setHidden( true );
					}
				}
				else if( tnm.equals( "row" ) )
				{
					int ht = -1, ixfe = 0;
					boolean customHeight = false;
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{ // r, v= row #+1, ht, ...
						String nm = xpp.getAttributeName( i );
						String v = xpp.getAttributeValue( i );
						if( nm.equals( "r" ) )
						{
							int rownum = Integer.valueOf( v ) - 1;
							r = insertRow( rownum,
							               false ); // now insertRow with no shift rows does NOT add a blank cell so no need to delete extra cell anymore
							r.setIxfe( getWorkBook().getDefaultIxfe() );
						}
						else if( nm.equals( "ht" ) )
						{
							ht = (int) (new Double( v ) * OOXMLReader.rowHtFactor);
						}
						else if( nm.equals( "s" ) )
						{   // customFormat?
							ixfe = Integer.valueOf( v );
						}
						else if( nm.equals( "customFormat" ) )
						{
							r.setIxfe( ixfe );
						}
						else if( nm.equals( "hidden" ) )
						{
							r.setHidden( true );
						}
						else if( nm.equals( "collapsed" ) )
						{       // 20090513 KSC: Added collapsed, outlineLevel [BUGTRACKER 2371]
							boolean h = r.isHidden(); // setCollapsed unconditionally sets hidden
							r.setCollapsed( true );
							if( !h )
							{
								r.setHidden( false );
							}
						}
						else if( nm.equals( "outlineLevel" ) )
						{
							r.setOutlineLevel( Integer.valueOf( v ) );
						}
						else if( nm.equals( "customHeight" ) )
						{
							customHeight = true;
						}
						else if( nm.equals( "thickBot" ) )
						{
							r.setHasAnyThickBottomBorder( true );
						}
						else if( nm.equals( "thickTop" ) )
						{
							r.setHasAnyThickTopBorder( true );
						}
						if( (ht != -1) && customHeight )    // if customHeight is NOT set do not set row height (encountered in Baxter XLSM templates)
						{
							r.setRowHeight( ht );
						}
					}
					// if customheight is NOT specified do not set row height
				}
				else if( tnm.equals( "c" ) )
				{// element c child v= value
					if( cellAddr != null )
					{
						if( r.getExplicitFormatSet() || (((formatId != getWorkBook().getDefaultIxfe()) && (formatId != 0))) )
						{ //default or not specified NOTE: default for OOXML is 0 not 15
							int[] rc = ExcelTools.getRowColFromString( cellAddr );
							OOXMLReader.sheetAdd( sheet, null, rc[0], rc[1], formatId );
						}
						cellAddr = null;
					}
					formatId = 0;
					type = "n"; // reset for those cells that don't specify a type, default = number
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{
						String nm = xpp.getAttributeName( i );    // r, s=style, t= type
						String v = xpp.getAttributeValue( i );
						if( nm.equals( "r" ) )
						{  // cell address
							cellAddr = v;      // save for setting later
						}
						else if( nm.equals( "s" ) )
						{
							formatId = Integer.valueOf( v );   // save for setting later
						}
						else if( nm.equals( "t" ) )
						{
							type = v;
						}
					}
					// would be great if could peek at next tag to determine
					// whether to add a blank cell here rather than catch it at
					// end tag below
				}
				else if( tnm.equals( "is" ) )
				{ // inline string child of <c cell element
					if( inlineStrs == null )
					{
						inlineStrs = new HashMap();
					}
					String s = OOXMLReader.getInlineString( xpp );
					inlineStrs.put( getSheetName() + "!" + cellAddr, s );
					int[] rc = ExcelTools.getRowColFromString( cellAddr );
					OOXMLReader.sheetAdd( sheet, "", rc[0], rc[1], formatId );   // add placeholder here
					cellAddr = null;
				}
				else if( tnm.equals( "f" ) )
				{ // formula
					if( cellAddr != null )
					{
						// do not process now since formulas may be dependent upon other sheet data; save and process after all sheets have been added
						String ftype = type, ref = "", si = "", ca = null;
						for( int i = 0; i < xpp.getAttributeCount(); i++ )
						{
							String nm = xpp.getAttributeName( i );
							String v = xpp.getAttributeValue( i );
							if( nm.equals( "t" ) )
							{
								ftype += "/" + v;      // add to data type the formula type:  shared, array, datatable, normal
							}
							else if( nm.equals( "ref" ) ) // only valid for master shared formula or array record
							{
								ref = v;
							}
							else if( nm.equals( "si" ) )  // shared index only valid for shared formulas
							{
								si = String.valueOf( Integer.parseInt( v ) + sfindex );
							}
							else if( nm.equals( "ca" ) )  // calculate cell, always set for volatile functions
							{
								ca = "1";
							}
						}
						String v = OOXMLReader.getNextText( xpp );
						formulas.add( new String[]{
								getSheetName(), cellAddr, "=" + v, si, ref, ftype, ca, Integer.valueOf( formatId ).toString(), ""
						} );
						type = "f"; // cell will not be added below; rather, formula cells are processed en mass in parse
					}
				}
				else if( tnm.equals( "v" ) )
				{
					/**
					 *     Cell Value
					 *     handle based upon cell data type
					 */
					if( cellAddr != null )
					{  // shouldn't be
						String v = OOXMLAdapter.getNextText( xpp );
						// use fast add method - uses int[] location
						int[] rc = ExcelTools.getRowColFromString( cellAddr );
						if( type.equals( "s" ) )
						{ // shared string
							// the SST has already been populated, now we just need to add
							// Labelsst recs and hook up with the isst.
							Labelsst labl = Labelsst.getPrototype( null, bk.getWorkBook() );
							labl.setIsst( Integer.valueOf( v ) );
							labl.setIxfe( formatId );
							addRecord( labl, rc );
						}
						else if( type.equals( "n" ) )
						{
							try
							{
								if( !v.equals( "null" ) )
								{
									OOXMLReader.sheetAdd( sheet, Integer.valueOf( v ), rc[0], rc[1], formatId );
								}
								else    // Should nepver get here
								{
									log.warn( "OOXMLAdapter.parse: Unexpected null encountered at: " + cellAddr );
								}
							}
							catch( NumberFormatException n )
							{ // could be a double or float instead of an int
								try
								{
									OOXMLReader.sheetAdd( sheet, new Double( v ), rc[0], rc[1], formatId );
								}
								catch( NumberFormatException nn )
								{
									OOXMLReader.sheetAdd( sheet, new Float( v ), rc[0], rc[1], formatId );
								}
							}
						}
						else if( type.equals( "b" ) )
						{
							boolean trx = (v.equals( "1" ) || v.equalsIgnoreCase( "true" ));
							OOXMLReader.sheetAdd( sheet, trx, rc[0], rc[1], formatId );
						}
						else if( type.equals( "f" ) )
						{ // grab cached value
							String[] s = (String[]) formulas.get( formulas.size() - 1 );
							s[8] = v;
							formulas.set( formulas.size() - 1, s );
						}
						else if( !type.equals( "e" ) )
						{ // added handling for 'e' type which is a formula as well (containing an ERR cachedval)
							OOXMLReader.sheetAdd( sheet, v, rc[0], rc[1], formatId );
						}
						cellAddr = null;   // denote we processed this cell
					}
				}
				else if( tnm.equals( "mergeCell" ) )
				{
					String ref = xpp.getAttributeValue( 0 );
					try
					{
						CellRange cr = new CellRange( getSheetName() + "!" + ref, bk );
						cr.mergeCells( false );
					}
					catch( CellNotFoundException e )
					{ /* necessary to report error?? */ }
				}
				else if( tnm.equals( "conditionalFormatting" ) )
				{
					Condfmt.parseOOXML( xpp, bk, this );
				}
				else if( tnm.equals( "dataValidations" ) )
				{
					Dval.parseOOXML( xpp, this );
				}
				else if( tnm.equals( "autoFilter" ) )
				{   // Appears to sometimes work in tandem with dataValidtions (see Modeling Workbook - WKSHT.xlsm)
					setOOAutoFilter( (org.openxls.formats.OOXML.AutoFilter) org.openxls.formats.OOXML.AutoFilter.parseOOXML( xpp ) );
				}
				else if( tnm.equals( "hyperlink" ) )
				{
					String ref = "", rid = "", desc = "";
					for( int i = 0; i < xpp.getAttributeCount(); i++ )
					{
						if( xpp.getAttributeName( i ).equals( "ref" ) )
						{
							ref = xpp.getAttributeValue( i );
						}
						else if( xpp.getAttributeName( i ).equals( "id" ) ) // external ref
						{
							rid = xpp.getAttributeValue( i );
						}
						else if( xpp.getAttributeName( i ).equals( "display" ) )    // display or description text
						{
							desc = xpp.getAttributeValue( i );
						}
						// TODO: Also handle location, tooltip ...
					}
					hyperlinks.add( new String[]{
							rid, ref, desc
					} ); // must save hyperlink refernce cell and id and link to target info in .rels file
					// External OOXML Objects controls=embedded controls, oleObject= embedded objects
					// These external objects contain link information which links to id's in vmlDrawingX.vml, activeX.xml ... must save and reset for later use
				}
				else if( tnm.equals( "pageSetup" ) )
				{      // scale orientation r:id ...
					addExternalInfo( shExternalLinkInfo, xpp );
				}
				else if( tnm.equals( "oleObject" ) )
				{      // progId  shapeId r:id ...
					addExternalInfo( shExternalLinkInfo, xpp );
				}
				else if( tnm.equals( "control" ) )
				{        // progId  shapeId r:id ...
					addExternalInfo( shExternalLinkInfo, xpp );
					// TODO: handle AlternateContent Machinery!
					// for now, we are ignoring choice and fallback and ONLY
					// extracting control element
				}
				else if( tnm.equals( "AlternateContent" ) )
				{   // defines a mechanism for the storage of content which is not defined by this Office Open XML
					// Standard, for example extensions developed by future software applications which leverage the Open XML formats
					// skip, for now - may have elements
					//     Choice->
					//         control->controlPr
					//     Fallback
					//         control
					// i.e. 1st choice is a control with control settings
					// if not possible, fallback is
				}
				else if( tnm.equals( "Fallback" ) )
				{
					OOXMLReader.getCurrentElement( xpp );    // skip as can replicate Choice
				}
				else if( tnm.equals( "controlPr" ) )
				{
					OOXMLReader.getCurrentElement( xpp );    // skip for now!!
				}
				else if( tnm.equals( "extLst" ) )
				{ // skip for now!!
					OOXMLReader.getCurrentElement( xpp );    // skip for now!!
				} /*else {
                         if (true)
                             log.warn("unprocessed XLSX sheet element: " + tnm);
                     }*/
			}
			else if( eventType == XmlPullParser.END_TAG )
			{
				String endTag = xpp.getName();
				if( endTag.equals( "row" ) && (cellAddr != null) )
				{

					int[] rc = ExcelTools.getRowColFromString( cellAddr );
					// if masking an explicit row format or if it's a unique format, set to new blank cell
					if( r.getExplicitFormatSet() || (((formatId != getWorkBook().getDefaultIxfe()) && (formatId != 0))) )
					{ //default or not specified NOTE: default for OOXML is 0 not 15 (unless converted from XLS ((:
//                         if (r.myRow.getExplicitFormatSet() || (/*formatId!=15 && */formatId!=0 && uniqueFormat)) { //default or not specified NOTE: default for OOXML==0 NOT 15
						OOXMLReader.sheetAdd( sheet, null, rc[0], rc[1], formatId );
//                         } else{
//                             sheetAdd(sheet,null,rc[0],rc[1],formatId);
					}
					cellAddr = null;
				}
				else /**/if( endTag.equals( "worksheet" ) )  // we're done!
				{
					break;
				}
			}
			eventType = xpp.next();
		}
	}

	/**
	 * NOTE: commentsX.xml also needs legacy drawing info (vmlDrawingX.vml)
	 * to define the text box itself including position and size, plus the vml elements
	 * also define whether the note is hidden
	 */
	void parseCommentsXML( WorkBookHandle bk, InputStream ii )
	{
		try
		{
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
			factory.setNamespaceAware( true );
			XmlPullParser xpp = factory.newPullParser();
			xpp.setInput( ii, null ); // using XML 1.0 specification
			int eventType = xpp.getEventType();

			Stack<String> lastTag = new java.util.Stack<String>();     // keep track of element hierarchy

			ArrayList<String> authors = new ArrayList<String>();
			String addr = "";
			int authId = -1;
			Unicodestring comment = null;
			//ignore for now: phonetic properties (phoneticPr), phonetic run (rPh)
			while( eventType != XmlPullParser.END_DOCUMENT )
			{
				if( eventType == XmlPullParser.START_TAG )
				{
					String tnm = xpp.getName();
					if( tnm.equals( "author" ) )
					{
						authors.add( OOXMLReader.getNextText( xpp ) );
					}
					else if( tnm.equals( "comment" ) )
					{
						if( (comment != null) && !"".equals( addr ) )
						{
							createNote( addr, comment, authors.get( authId ) );
						}
						addr = xpp.getAttributeValue( "", "ref" );
						authId = Integer.valueOf( xpp.getAttributeValue( "", "authorId" ) );
						comment = null;
					}
					else if( tnm.equals( "text" ) )
					{
						// read in text element
						lastTag.push( tnm );
						Text t = Text.parseOOXML( xpp, lastTag, bk );
						// don't reset state vars as can there can be more
						comment = t.getCommentWithFormatting();
					}
				}
				else if( eventType == XmlPullParser.END_TAG )
				{
				}
				eventType = xpp.next();
			}
			if( !"".equals( comment ) && !"".equals( addr ) )
			{
				createNote( addr, comment, authors.get( authId ) );
			}
		}
		catch( Exception e )
		{
			log.error( "OOXMLAdapter.parseCommentsXML: " + e.toString() );
		}
		return;
	}

	/**
	 * parse vml - legacy drawing info e.g. mso shapes and lines + note textboxes
	 * <br>for now, legacy drawing info is just stored and not parsed into BIFF8 structures
	 * <br>i.e. store everything but note textboxes at this time; intention is later on
	 * to store all mso shapes and objects in BIFF8 records
	 * <br>this vml is stored at the sheet level in the boundsheet's OOXMLShapes storage
	 * <br>Notes textboxes are being created upon writeLegacyDrawingXML
	 *
	 * @param bk
	 * @param sheet
	 * @param ii
	 * @return StringBuffer rep of saved vml
	 */
	StringBuffer parseLegacyDrawingXML( WorkBookHandle bk, InputStream ii )
	{
		/**
		 * more info:
		 * The Shape element is the basic building block of VML. A shape may exist on its own or within a Group
		 element. Shape defines many attributes and sub-elements that control the look and behavior of the shape. A
		 shape must define at least a Path and size (Width, Height). VML 1 also uses properties of the CSS2 style
		 attribute to specify positioning and sizing

		 The ShapeType element defines a definition, or template, for a shape. Such a template is “instantiated” by
		 creating a Shape element that references the ShapeType. The shape can override any value specified by its
		 ShapeType, or define attributes and elements the ShapeType does not provide. A ShapeType may not
		 reference another ShapeType.
		 The attributes and elements a ShapeType uses are identical to those of the Shape element, with these
		 exceptions: ShapeType may not use the Type element, Visibility is always hidden.

		 Regarding Notes:
		 The visible box shown for comments attached to cells is persisted using VML. The comment contents are
		 stored separately as part of SpreadsheetML.
		 */
		StringBuffer savedVml = new StringBuffer();
		try
		{
			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
			factory.setNamespaceAware( true );
			XmlPullParser xpp = factory.newPullParser();
			xpp.setInput( ii, null ); // using XML 1.0 specification
			int eventType = xpp.getEventType();
			// NOTE: since vml controls visibility (hidden or shown), text box size, etc., notes are created upon VML parsing
			// and edited here for the actual text and formats ... ms's legacy drawing stuff makes for alot of convoluted processing ((;
			FastAddVector nhs = new FastAddVector();
			{
				CommentHandle[] anhs = bk.getWorkSheet( getSheetName() ).getCommentHandles();
				for( CommentHandle anh : anhs )
				{
					nhs.add( anh );
				}
			}
			while( eventType != XmlPullParser.END_DOCUMENT )
			{
				if( eventType == XmlPullParser.START_TAG )
				{
					String tnm = xpp.getName();
					if( tnm.equals( "shapelayout" ) )
					{
						// just store
						savedVml.append( OOXMLReader.getCurrentElement( xpp ) );
					}
					else if( tnm.equals( "shapetype" ) )
					{
						// if spt==202 (shape-type=text box) id="_x0000_t202" then it's note textbox
						if( !xpp.getAttributeValue( "urn:schemas-microsoft-com:office:office", "spt" ).equals( "202" ) )
						{// if it's not a note textbox shapetype, store it
							savedVml.append( OOXMLReader.getCurrentElement( xpp ) );
						}
						else // ignore element - will be rebuilt upon write
						{
							OOXMLReader.getCurrentElement( xpp );
						}
					}
					else if( tnm.equals( "shape" ) )
					{   // this is basic
						// several types: can contain images, shapes and notes
						if( !xpp.getAttributeValue( "", "type" ).endsWith( "_x0000_t202" ) )
						{// if it's not a note textbox, save it
							// if type="#_x0000_t202" it's a note textbox
							savedVml.append( OOXMLReader.getCurrentElement( xpp ) );
						}
						else
						{// add note here, text and formatting will be input upon Comments parse;
							int r = -1, c = -1;
							boolean visible = false;
							short[] bounds = new short[8];
							while( eventType != XmlPullParser.END_DOCUMENT )
							{
								if( eventType == XmlPullParser.START_TAG )
								{
									tnm = xpp.getName();    // Anchor
									if( tnm.equals( "Row" ) )
									{
										r = Integer.valueOf( OOXMLReader.getNextText( xpp ) );
									}
									else if( tnm.equals( "Column" ) )
									{
										c = Integer.valueOf( OOXMLReader.getNextText( xpp ) );
									}
									else if( tnm.equals( "Visible" ) )
									{
										visible = true;
									}
									else if( tnm.equals( "Anchor" ) )
									{
										// get a string rep of the bounds
										String sbounds = OOXMLReader.getNextText( xpp );
										// prepare for parsing
										sbounds = sbounds.replaceAll( "[^0-9,]+", "" );
										String[] s = sbounds.split( "," );
										for( int i = 0; i < 8; i++ )
										{
											bounds[i] = Short.valueOf( s[i] );
										}
									}
								}
								else if( eventType == XmlPullParser.END_TAG )
								{
									if( xpp.getName().equals( "shape" ) )
									{
										break;
									}
								}
								eventType = xpp.next();
							}
							String addr = ExcelTools.formatLocation( new int[]{ r, c } );
							for( int i = 0; i < nhs.size(); i++ )
							{
								CommentHandle nh = (CommentHandle) nhs.get( i );
								if( nh.getAddress().endsWith( addr ) )
								{
									if( visible )
									{
										nh.show();
									}
									nh.setTextBoxBounds( bounds );
									nhs.remove( i );
									break;
								}
							}
						}
					}
					else if( tnm.equals( "xml" ) )
					{ // ignore :)
					}
					else if( tnm.equals( "imagedata" ) )
					{
					}
					else
					{ // just store  --
						savedVml.append( OOXMLReader.getCurrentElement( xpp ) );
					}
				}
				else if( eventType == XmlPullParser.END_TAG )
				{
				}
				eventType = xpp.next();
			}
		}
		catch( Exception e )
		{
			log.error( "OOXMLAdapter.parseLegacyDrawingXML: " + e.toString() );
		}
		return savedVml;
	}

	/**
	 * given drawingML drawing.xml inputstream, parse each twoCellAnchor tag into appropriate image or chart and insert into sheet
	 *
	 * @param bk
	 * @param sheet
	 * @param imgFiles list of image or chart files (referenced in drawing.xml via rId)
	 * @param ii       InputStream
	 * @param zip      Current Open ZipOutputStream
	 */
	void parseDrawingXML( WorkBookHandle bk, ArrayList drawingFiles, InputStream ii, ZipFile zip, String parentDir, String externalDir )
	{
		try
		{
			Stack<String> lastTag = new java.util.Stack<String>();     // keep track of element hierarchy

			XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
			factory.setNamespaceAware( true );
			XmlPullParser xpp = factory.newPullParser();

			xpp.setInput( ii, null ); // using XML 1.0 specification
			int eventType = xpp.getEventType();
			while( eventType != XmlPullParser.END_DOCUMENT )
			{
				if( eventType == XmlPullParser.START_TAG )
				{
					String tnm = xpp.getName();
					if( tnm.equals( "twoCellAnchor" ) )
					{   // beginning of DrawingML for a single image or chart
						lastTag.push( tnm );
						// TODO: handle group shapes which combine images, shapes and/or charts ********************************************************
						TwoCellAnchor t = (TwoCellAnchor) TwoCellAnchor.parseOOXML( xpp, lastTag, bk ).cloneElement();
						if( t.hasImage() )
						{
							String s = t.getEmbed();    // rid of embedded object
							if( s.indexOf( "rId" ) == 0 )
							{ // should!
								String imgFile = OOXMLReader.parsePathForZip( OOXMLReader.getFilename( drawingFiles, s ), parentDir );
								ZipEntry img = new ZipEntry( imgFile );
								BufferedInputStream is = new BufferedInputStream( zip.getInputStream( img ) );
								ImageHandle im = new ImageHandle( is, this );
								insertImage( im );
								im.setName( t.getName() );
								im.setShapeName( t.getDescr() );
								im.setBounds( TwoCellAnchor.convertBoundsToBIFF8( this, t.getBounds() ) );        // must do after insert
								im.setSpPr( t.getSppr() );            // set image shape properties
								im.setEditMovement( t.getEditAs() );  // specify how to resize or move
								im.update();                        // update underlying image record with set data
							}
						}
						else if( t.hasChart() )
						{
							String s = t.getChartRId();
							if( s.indexOf( "rId" ) == 0 )
							{ // should!
								String chartfilename = OOXMLReader.getFilename( drawingFiles, s );
								String name = t.getName();
								if( (name == null) || name.equals( "null" ) )
								{
									name = "Untitled Chart";
								}
								ChartHandle ch = bk.createChart( name, bk.getWorkSheet( getSheetName() ) );
								ch.setRelativeBounds( TwoCellAnchor.convertBoundsToBIFF8( this,
								                                                          t.getBounds() ) );        // must do after insert
								ch.setEditMovement( t.getEditAs() );  // specify how to resize or move
								ch.setOOXMLName( name );
								chartfilename = OOXMLReader.parsePathForZip( chartfilename, parentDir );
								ZipEntry chFile = new ZipEntry( chartfilename );
								// must account for default chart settings: set fontx recs to default font for this workbook ...
								ch.resetFonts();    // reset all fonts for the chart
								ch.removeLegend(); // not all charts have legends!
								int ps = chartfilename.lastIndexOf( "/" ) + 1;
								ZipEntry rels = OOXMLReader.getEntry( zip, chartfilename.substring( 0,
								                                                                    ps ) + "_rels/" + chartfilename.substring(
										ps ) + ".rels" );
								if( rels != null )
								{ // chart file has embeds - usually drawing ml which defines userShapes
//xxx TODO: REFACTOR to get these specifics out
									ArrayList chartEmbeds = OOXMLReader.parseRels( OOXMLReader.wrapInputStream( zip.getInputStream( rels ) ) );
									for( Object chartEmbed : chartEmbeds )
									{
										String[] dr = (String[]) chartEmbed;
										if( dr[0].equals( "userShape" ) )
										{ // should!
											dr[1] = dr[1].substring( dr[1].lastIndexOf( "/" ) + 1 );
											ch.addChartEmbed( new String[]{ dr[0], externalDir + dr[1] } );
											OOXMLReader.passThrough( zip,
											                         parentDir + dr[1],
											                         externalDir + dr[1] );   // Store Embedded Object on disk for later retrieval
										}
										else if( dr[0].equals( "image" ) )
										{
											String parentp = OOXMLReader.parsePathForZip( dr[1], parentDir );
											parentp = parentp.substring( 0, parentp.lastIndexOf( "/" ) + 1 );
											dr[1] = dr[1].substring( dr[1].lastIndexOf( "/" ) + 1 );
											ch.addChartEmbed( new String[]{ dr[0], externalDir + dr[1] } );
											OOXMLReader.passThrough( zip,
											                         parentp + dr[1],
											                         externalDir + dr[1] ); // save the original target file for later re-packaging
										}
										else if( dr[0].equals( "themeOverride" ) )
										{
											String parentp = OOXMLReader.parsePathForZip( dr[1], parentDir );
											parentp = parentp.substring( 0, parentp.lastIndexOf( "/" ) + 1 );
											dr[1] = dr[1].substring( dr[1].lastIndexOf( "/" ) + 1 );
											ch.addChartEmbed( new String[]{ dr[0], externalDir + dr[1] } );
											ZipEntry target = OOXMLAdapter.getEntry( zip, parentp + dr[1] );
											bk.getWorkBook().getTheme().parseOOXML( bk, OOXMLAdapter.wrapInputStream( zip.getInputStream(
													target ) ) );
										}
										else
										{
											log.warn( "OOXMLAdapter.parseDrawingML: unknown chart embed " + dr[0] );
										}
									}
								}
								// do after parsing rels in case there is override theme colors ...
								ch.parseOOXML( OOXMLReader.wrapInputStream( zip.getInputStream( chFile ) ) );
							}
						}
						else if( t.hasShape() )
						{
							addOOXMLShape( t );       // just store shape for later output since prev. versions do not handle shapes
							if( t.getEmbed() != null )
							{                  // if this shape has embedded objects such as images
								String imgFile = OOXMLReader.parsePathForZip( OOXMLReader.getFilename( drawingFiles, t.getEmbed() ),
								                                              parentDir );    // look up embedded rid in content list to get filename
								t.setEmbedFilename( imgFile );        // save embedded filename for later retrieval
								OOXMLReader.passThrough( zip,
								                         imgFile,
								                         externalDir + imgFile );   // Store Embedded Object on disk for later retrieval
							}
						}
						else
						{   // TESTING!
							log.error( "OOXMLAdapter.parseDrawingXML: Unknown twoCellAnchor type" );
						}
					}
					else if( tnm.equals( "oneCellAnchor" ) )
					{  // unclear if this can be root of charts and images as well as shapes
						lastTag.push( tnm );
						OneCellAnchor oca = (OneCellAnchor) OneCellAnchor.parseOOXML( xpp, lastTag, bk ).cloneElement();
						if( oca.hasImage() )
						{
							String s = oca.getEmbed();  // rid of embedded object
							if( s.indexOf( "rId" ) == 0 )
							{ // should!
								String imgFile = OOXMLReader.parsePathForZip( OOXMLReader.getFilename( drawingFiles, s ), parentDir );
								ZipEntry img = new ZipEntry( imgFile );
								BufferedInputStream is = new BufferedInputStream( OOXMLReader.wrapInputStream( zip.getInputStream( img ) ) );
								ImageHandle im = new ImageHandle( is, this );
								insertImage( im );
								im.setName( oca.getName() );
								im.setShapeName( oca.getDescr() );
								im.setBounds( oca.getBounds() );        // must do after insert
								im.setSpPr( oca.getSppr() );          // set image shape properties
								im.update();                        // update underlying image record with set data
							}
						}
						else if( oca.hasChart() )
						{
							String s = oca.getEmbed();
							if( s.indexOf( "rId" ) == 0 )
							{ // should!
								String chart = OOXMLReader.getFilename( drawingFiles, s );
								String name = oca.getName();
								if( (name == null) || name.equals( "null" ) )
								{
									name = "Untitled Chart";
								}
								ChartHandle ch = bk.createChart( name, bk.getWorkSheet( getSheetName() ) );
								ch.setRelativeBounds( oca.getBounds() );
								//ch.setChartTitle(name);
								chart = OOXMLReader.parsePathForZip( chart, parentDir );
								ZipEntry chFile = new ZipEntry( chart );
								ch.parseOOXML( OOXMLReader.wrapInputStream( zip.getInputStream( chFile ) ) );
							}
						}
						else if( oca.hasShape() )
						{
							addOOXMLShape( oca );     // just store shape for later output since prev. versions do not handle shapes
						}
						else
						{   // TESTING!
							log.error( "OOXMLAdapter.parseDrawingXML: Unknown oneCellAnchor type" );
						}
					}
					else if( tnm.equals( "userShapes" ) )
					{    // drawings ONTOP of charts = Reference to Chart Drawing Part
						log.error( "OOXMLAdapter.parseDrawingXML: USER SHAPE ENCOUNTERED" );
					}
				}
				eventType = xpp.next();
			}
		}
		catch( Exception e )
		{
			log.error( "OOXMLAdapter.parseDrawingXML: failed " + e.toString() );
		}
	}

	/**
	 * Shifts a single column.
	 * This adjusts any mention of the column number in the associated records.
	 * References are not handled; for those see {@link ReferenceTracker}.
	 *
	 * @param col   the column to be shifted
	 * @param shift the number of columns by which to shift
	 */
	private void shiftCol( int colNum, int shift )
	{
		Colinfo info = getColInfo( colNum );
		int oldCol = colNum;
		int newCol = oldCol + shift;

		List<BiffRec> cells;
		try
		{
			cells = getCellsByCol( colNum );
			for( BiffRec cell : cells )
			{
				cell.setCol( (short) newCol );
				updateDimensions( cell.getRowNumber(), cell.getColNumber() );
			}
		}
		catch( CellNotFoundException e )
		{
			// No cells exist in this column
		}

		if( null != info )
		{
			int first = info.getColFirst();
			if( (first == oldCol) || (first > newCol) )
			{
				info.setColFirst( newCol );
			}

			int last = info.getColLast();
			if( (last == oldCol) || (last < newCol) )
			{
				info.setColLast( newCol );
			}
		}
	}

	private void removeColInfo( Colinfo ci )
	{
		removeRecFromVec( ci );
		colinfos.remove( ci );
	}

	/**
	 * Shifts a single row.
	 * This adjusts any mention of the row number in the row records. Formula
	 * references are not handled; for those see {@link ReferenceTracker}.
	 *
	 * @param row   the row to be shifted
	 * @param shift the number of rows by which to shift
	 */
	private void shiftRow( Row row, int shift )
	{
		Iterator<BiffRec> cells = row.getCells().iterator();
		Mulblank skipMulBlank = null;
		while( cells.hasNext() )
		{
			BiffRec cell = cells.next();

			if( cell == skipMulBlank )
			{
				continue;
			}
			if( cell.getOpcode() == MULBLANK )
			{
				skipMulBlank = (Mulblank) cell;
			}

			shiftCellRow( cell, shift );
		}

		int oldRow = row.getRowNumber();
		int newRow = oldRow + shift;
		row.setRowNumber( newRow );

		rows.remove( oldRow );
		rows.put( newRow, row );

		if( dimensions.getRowLast() < newRow )
		{
			dimensions.setRowLast( newRow );
			lastRow = row;
		}
	}

	/**
	 * add a row to the worksheet as well
	 * as to the RowBlock which will handle
	 * the updating of Dbcell index behavior
	 *
	 * @param BiffRec the cell being added (can't add a row without one...)
	 */
	private Row addNewRow( BiffRec cell )
	{
		int rn = cell.getRowNumber();
		if( getRowByNumber( rn ) != null )
		{
			return getRowByNumber( rn ); // already exists!
		}
		Row r = new Row( rn, wkbook );
		try
		{    //Out-of-spec wb's may not have dimensions record -- will be handled upon validation
			if( rn >= getMaxRow() )
			{
				dimensions.setRowLast( rn );
			}
		}
		catch( NullPointerException e )
		{
		}
		r.setSheet( this );
		addRowRec( r );
		return r;

	}

	/**
	 * Creates a valrec (Value containing XLSRecord).   This method observes
	 * the object passed in, then creates a XLS record of the correct type depending
	 * on the object type. A default FormatID is handled as well.
	 * <p/>
	 * The valrec at this point is not fully formed, it needs the row/col set
	 * along with some other default actions that occur in addRecord(). This is
	 * due to addRecord being the merge point for adding cells to a boundsheet between
	 * the parse-level additions and the user-level additions!
	 *
	 * @param obj        the value of the new Cell
	 * @param row        & col address of the new Cell
	 * @param FORMAT_ID, index to the XF record for this valrec
	 * @return partially formed XLS Record.
	 */
	private XLSRecord createValrec( Object obj, int[] rc, int FORMAT_ID )
	{
		/*try{
			BiffRec cx = this.getCell(rc[0],rc[1]);
			this.removeCell(cx);
		}catch(CellNotFoundException e){}*/
		XLSRecord rec;
		if( obj == null )
		{
			rec = new Blank();
		}
		else if( obj instanceof Formula )
		{
			rec = (Formula) obj;
		}
		else if( obj instanceof Double )
		{
			rec = new NumberRec( (Double) obj );
		}
		else if( obj instanceof String )
		{
			if( ((String) obj).startsWith( "=" ) )
			{
				try
				{
					rec = FormulaParser.getFormulaFromString( (String) obj, this, rc );
				}
				catch( Exception e )
				{
					throw new FunctionNotSupportedException( "Adding new Formula at " + getSheetName() + "!" + ExcelTools.formatLocation( rc ) + " failed: " + e
							.toString() + "." );
				}
			}
			else if( ((String) obj).startsWith( "{=" ) )
			{
				// interpret array formulas as well  20090526 KSC: changed from "{" to "{=" tracy vo complex string addition
				try
				{
					rec = FormulaParser.getFormulaFromString( (String) obj, this, rc );
					rec.isFormula = true;
				}
				catch( Exception e )
				{
					throw new FunctionNotSupportedException( "Adding new Formula at " + getSheetName() + "!" + ExcelTools.formatLocation( rc ) + " failed: " + e
							.toString() + "." );
				}
			}
			else if( obj.toString().equalsIgnoreCase( "" ) )
			{
				rec = new Blank();
			}
			else
			{
				rec = Labelsst.getPrototype( (String) obj, getWorkBook() );
			}
		}
		else if( obj instanceof Integer )
		{
			int l = (Integer) obj;
			rec = new NumberRec( l );

		}
		else if( obj instanceof Long )
		{
			long l = (Long) obj;
			rec = new NumberRec( l );
		}
		else if( obj instanceof Boolean )
		{
			// log.error("Adding Boolean Not Implemented");
			rec = Boolerr.getPrototype();
			rec.setBooleanVal( ((Boolean) obj).booleanValue() );
		}
		else
		{
			log.warn( "Assuming double value - will convert supplied Object '{}' to java.lang.String first.", obj.getClass().getName() );
			double d = new Double( String.valueOf( obj ) );        // 20080211 KSC: Double.valueOf(String.valueOf(obj)).doubleValue();
			rec = new NumberRec( d );
		}
		rec.setWorkBook( getWorkBook() );
		rec.setXFRecord( FORMAT_ID );
		// 20100607 KSC: update maxrow/maxcol if necessary
		if( (rc[0] > getMaxRow()) || (rc[1] > getMaxCol()) )
		{
			updateDimensions( rc[0], rc[1] );
		}
		return rec;
	}

	private boolean copyPriorCellFormatForNewCells( BiffRec c )
	{
		int row = c.getRowNumber() + 1; // get the prior cell addy
		String cnm = ExcelTools.getAlphaVal( c.getColNumber() );
		BiffRec ch = getCell( cnm + row ); // try it...
		if( ch == null )
		{
			return false;
		}
		c.setIxfe( ch.getIxfe() );
		return true;
	}

	/**
	 * Get the built in names referring to this boundsheet
	 *
	 * @return
	 */
	private ArrayList<Name> getBuiltInNames()
	{
		ArrayList<Name> retlist = new ArrayList<Name>();
		Name[] ns = getWorkBook().getNames();
		for( Name n : ns )
		{
			if( n.isBuiltIn() && ((n.getIxals() == (getSheetNum() + 1)) || (n.getItab() == (getSheetNum() + 1))) )
			{
				retlist.add( n );
			}
		}
		return retlist;
	}

	/**
	 * adds the _FILTERDATABASE name necessary for AutoFilter
	 * if not already presetn
	 */
	private void addFilterDatabase()
	{
		List<Name> names = getBuiltInNames();
		Name n = null;
		for( int i = 0; (i < names.size()) && (n == null); i++ )
		{
			if( (names.get( i )).getBuiltInType() == Name._FILTER_DATABASE )
			{
				n = names.get( i );
			}
		}
		if( n == null )
		{ // not present
			try
			{
				n = new Name( getWorkBook(), "Built-in: _FILTER_DATABASE" );
				n.setBuiltIn( Name._FILTER_DATABASE );
				int xref = getWorkBook().getExternSheet( true ).insertLocation( getSheetNum(), getSheetNum() );
				n.setExternsheetRef( xref );
				n.updateSheetReferences( this );
				n.setSheet( this );
				n.setIxals( (short) (getSheetNum()/* +1 */) );
				n.setItab( (short) (getSheetNum() + 1) );
				String loc = ExcelTools.formatLocation( new int[]{
						getMinRow(), getMinCol(), getMaxRow() - 1, getMaxCol() - 1
				}, false, false );
				Stack<Ptg> s = new Stack<Ptg>();
				s.push( PtgRef.createPtgRefFromString( getSheetName() + "!" + loc, n ) );
				n.setExpression( s );
			}
			catch( Exception e )
			{

			}
		}
	}

	@Override
	public void setWsBool( WsBool ws )
	{
		wsbool = ws;
	}

	/**
	 * remove the _FILTER_DATABASE name (necessary for AutoFilters) for this sheet
	 */
	private void removeFilterDatabase()
	{
		List<Name> names = getBuiltInNames();
		Name n = null;
		try
		{
			for( int i = 0; (i < names.size()) && (n == null); i++ )
			{
				if( (names.get( i )).getBuiltInType() == Name._FILTER_DATABASE )
				{
					n = names.get( i );
					getWorkBook().removeName( n );
					break;
				}
			}
		}
		catch( Exception e )
		{
		}
	}

	/**
	 * Handles the MSO manipulations necessary for creating a note record
	 * <p/>
	 * // For each note:
	 * // [msodrawing
	 * //  obj - ftNts note
	 * //  msodrawing - attached shape
	 * //  Txo (text object), continue, continue] x n
	 * // [note 1] x n
	 * // window 2
	 * // ************************************************************************************
	 * // NOTE:
	 * // SOME TEMPLATES HAVE [obj= ftNts, Continue, Txo, continue, continue, continue]
	 * // MORE INFO (get this):
	 * // Obj, Continue= 2nd MSO!!!, Txo, Continue, Continue, Continue= 1st MSO!!!
	 * // ************************************************************************************
	 *
	 * @param coords rowcol of the note record
	 * @return insertion index for note
	 */
	private int insertMSOObjectsForNote( int[] coords )
	{
		int insertIndex;
		MSODrawingGroup msodg = wkbook.getMSODrawingGroup();
		if( msodg == null )
		{
			msodg = wkbook.createMSODrawingGroup();
			msodg.initNewMSODrawingGroup();
		}

		// insert either above first NOTE record or before WINDOW2 and certain other XLSRECORDS
		insertIndex = getIndexOf( NOTE );
		if( insertIndex == -1 ) // no existing notes - find proper insert index
		{
			insertIndex = getIndexOf( WINDOW2 );
		}
		while( (insertIndex - 1) > 0 )
		{
			short opc = ((BiffRec) SheetRecs.get( insertIndex - 1 )).getOpcode();
			if( (opc == MSODRAWING) || (opc == CONTINUE) )
			{
				MSODrawing rec;
				if( opc == MSODRAWING )
				{
					rec = ((MSODrawing) SheetRecs.get( insertIndex - 1 ));
				}
				else
				{
					rec = ((Continue) SheetRecs.get( insertIndex - 1 )).maskedMso;
					if( rec == null )
					{
						break;
					}
				}
				if( rec.getSOLVERContainerLength() == 0 )
				{
					break;    // solver containers must be last, apparently ... sigh ...
				}
				// else
				// log.info("Boundsheet.InsertMSOObjectsForNote.  SOLVER CONTAINER ENCOUNTED");
			}
			else if( (opc == OBJ) || (opc == CONTINUE) || (opc == DIMENSIONS) || (opc == 0x866) || (opc == 0x1C2) )
			{
				break;
			}
			insertIndex--;
		}

		MSODrawing msoheader = msodg.getMsoHeaderRec( this );
		MSODrawing msoDrawing = (MSODrawing) MSODrawing.getPrototype();
		msoDrawing.setSheet( this );
		msoDrawing.setWorkBook( getWorkBook() );
		if( msoheader == null )
		{
			msoDrawing.setIsHeader();
			msoheader = msoDrawing;
		}

		// mso record which creates a text box
		msoDrawing.createCommentBox( coords[0], coords[1] );
		SheetRecs.add( insertIndex++, msoDrawing );
		msoheader.numShapes++;
		msodg.addMsodrawingrec( msoDrawing ); // add the new drawing rec to the msodrawinggroup set of recs

		// object record which defines a basic note
		Obj obj = Obj.getBasicObjRecord( Obj.otNote, ++lastObjId );    // create a note object
		SheetRecs.add( insertIndex++, obj );

		// now add attached text-type mso, specifying the shape has attached text
		msoDrawing = (MSODrawing) MSODrawing.getTextBoxPrototype();
		msoDrawing.setSheet( this );
		SheetRecs.add( insertIndex++, msoDrawing );
		msodg.addMsodrawingrec( msoDrawing ); // add the new drawing rec to the msodrawinggroup set of recs

		// now update msodg + msoheader rec
		wkbook.updateMsodrawingHeaderRec( this );     // find the msodrawing header record and update it (using info from other msodrawing recs)
		msodg.setSpidMax( wkbook.lastSPID + 1 );
		msodg.updateRecord();       // given all information, generate appropriate bytes for the Mso rec
		msodg.dirtyflag = true;
		return insertIndex;
	}

	/**
	 * inserts a row and shifts all of the other rows down one
	 * <p/>
	 * the rownum is zero based.  calling insertrow(9,true) will
	 * create a row containing A10, and subsequently shift rows > 9 by 1.
	 *
	 * @return the row that was just inserted
	 */
	private Row insertRow( int rownum, boolean shiftrows )
	{
		return insertRow( rownum, WorkSheetHandle.ROW_INSERT_MULTI, shiftrows );
	}

	@Override
	public WsBool getWsBool()
	{
		return wsbool;
	}

	// TODO: Handle below options in Excel 2003 i.e. create appropriate records *************************************************************

}