/**
Copyright Dominik Downarowicz 2022
https://github.com/HealPoint/j3270Server
Based on https://github.com/racingmars/go3270
LICENSE in the project root for license information

**/
package at.downardo.j3270Server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Screen is an array of fields which compose a complete 3270 screen.
 * No checing is performed for lack of overlapping fields, uniqure field names,
 * @author downarowiczd
 *
 */
public class Screen {
	
	//private Field[] fields;
	private ArrayList<Field> fields;
	
	/*public Screen(Field[] _fields) {
		this.fields = _fields;
	}*/
	public Screen(ArrayList<Field> _fields) {
		this.fields = _fields;
	}
	
	public ArrayList<Field> getFields() {
		return fields;
	}
	
	/**
	 * ShowScreen writes the 3270 datastream for the screen to a connection.
	 * Fields that aren't valid (e.g. outside of the 24x80 screen) are silently
	 * ignored. If a named field has an entry in the values map, the content of
	 * the field from the values map is used INSTEAD OF the Field struct's Content
	 * field. The values map may be nil if no overrides are needed. After writing
	 * the fields, the cursor is set to crow, ccol, which are 0-based positions:
	 * row 0-23 and col 0-79. Errors from conn.Write() are returned if
	 * encountered.
	 * @param screen
	 * @param crow
	 * @param ccol
	 * @param buffer
	 * @throws IOException
	 */
	public static Response ShowScreen(Screen screen, HashMap<String, String> values, int crow, int ccol, BufferedOutputStream buffer, BufferedInputStream in) throws IOException {
		//ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		
		HashMap<Integer, String> fieldMap = new HashMap<Integer, String>();

		buffer.write(0xf5);
		
		buffer.write(0xc3);
		
		
		for(Field fld : screen.getFields()) {
			if(fld.getRow() < 0 || fld.getRow() > 23 || fld.getCol() < 0 || fld.getCol() > 79) {
				continue;
			}
			
			for(int _t : Screen.sba(fld.getRow(), fld.getCol())){
				buffer.write(_t);
			}
			
			for(int _t : Screen.buildField(fld)){
				buffer.write(_t);
			}
			
			/*if(fld.getContent() != "") {
				for (int _t : EBCDIC.ascii2ebcdic(fld.getContent().getBytes())) {
					buffer.write(_t);
				}
			}*/
			
			
			String content = fld.getContent();
			
			if(fld.getName() != "") {
				if(values != null & values.containsKey(fld.getName())) {
					content = values.get(fld.getName());
				}
			}
			
			if(content != "") {
				for (int _t : EBCDIC.ascii2ebcdic(content.getBytes())) {
					buffer.write(_t);
				}
			}
			
			if(fld.isWrite()) {
				int bufaddr = fld.getRow() * 80 + fld.getCol();
				fieldMap.put(bufaddr+1, fld.getName());
			}
			
			
		}
		
		if(crow < 0 || crow > 23) {
			crow = 0;
		}
		
		if(ccol < 0 || ccol > 79) {
			ccol = 0;
		}
		buffer.write(ic(crow, ccol));
		
		//Telnet IAC EOR
		byte[] _t = new byte[]{(byte) 0xff, (byte) 0xef};
		
		try {
			buffer.write(_t);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		buffer.flush();
		
		Response response = Response.readResponse(in, fieldMap);
		
		for(Field fld : screen.getFields()) {
			if(!fld.KeepSpaces && response.Values != null) {
				if(response.Values.get(fld.getName()) != "" && response.Values.get(fld.getName()) != null) {
					response.Values.put(fld.getName(), response.Values.get(fld.getName()).trim());
				}
			}
		}
		
		return response;
		
	}
	
	/**
	 * sba is the "set buffer address" 3270 command
	 * @param row
	 * @param col
	 * @return
	 */
	public static int[] sba(int row, int col) {
		int[] _return = new int[3];
		
		_return[0] = 0x11;
		_return[1] = Screen.getPos(row, col)[0];
		_return[2] = Screen.getPos(row, col)[1];
		return _return;
	}
	
	/**
	 * sf is the "start field" 3280 command
	 * @param write
	 * @param intense
	 * @param hidden
	 * @return
	 */
/*	public static int[] sf(boolean write, boolean intense, boolean hidden) {
		int[] _return = new int[2];
		
		_return[0] = 0x1d;
		
		if(!write) {
			_return[1] |= 1 << 5;
		}else {
			_return[1] |= 1;
		}
		
		if(intense) {
			_return[1] |= 1 << 3;
		}
		
		if(hidden) {
			_return[1] |= 1 << 3;
			_return[1] |= 1 << 2;
		}
		
		
		_return[1] = Util.codes[_return[1]];
		
		return _return;

	}*/
	
	
	
	/**
	 * ic is the "insert cursor" 3270 command. This function will include the appropriate SBA command
	 * @param row
	 * @param col
	 * @return
	 */
	public static byte[] ic(int row, int col) {
		byte[] _return = new byte[4];
		
		int x=0;
		for(int _t : Screen.sba(row, col)) {
			_return[x] = (byte)_t;
			x++;
		}
		
		_return[3] = 0x13;
		
		
		return _return;
	}
	
	/**
	 * getpost translates row and col to buffer address control characters.
	 * @param row
	 * @param col
	 * @return
	 */
	public static int[] getPos(int row, int col) {
		int[] _return = new int[2];
		
		int address = row * 80 + col;
		int hi = (address & 0xfc0) >> 6;
		int low = address & 0x3f;
		
		_return[0] = Util.codes[hi];
		_return[1] = Util.codes[low];
		
		return _return;
	}
	/**
	 * Will return either an sf or sfe command depending for the field
	 * @param f
	 * @return
	 */
	public static int[] buildField(Field f) {
		List<Integer> _return = new ArrayList<Integer>();
		
		if(f.getColour() == Field.Colour.DefaultColour && f.getHighlight() == Field.Highlight.DefaultHighlight) {
			_return.add(0x1d); //sf - "start field"
			_return.add(sfAttribute(f.isWrite(), f.isIntense(), f.isHidden()));
			
			return Util.convertIntegers(_return);
		}
		
		_return.add(0x29); //sfe - "start field extended"
		int paramCount = 1;
		if(f.getColour() != Field.Colour.DefaultColour) {
			paramCount++;
		}
		if(f.getHighlight() != Field.Highlight.DefaultHighlight) {
			paramCount++;
		}
		_return.add(paramCount);
		
		//Write the basic field attribute
		_return.add(0xc0);
		_return.add(sfAttribute(f.isWrite(), f.isIntense(), f.isHidden()));
		
		if(f.getHighlight() != Field.Highlight.DefaultHighlight) {
			_return.add(0x41);
			_return.add(f.getHighlight().value);
		}
		
		if(f.getColour() != Field.Colour.DefaultColour) {
			_return.add(0x42);
			_return.add(f.getColour().value);
		}
		
		return Util.convertIntegers(_return);
		
	}
	
	
	public static int sfAttribute(boolean write, boolean intense, boolean hidden) {
		int _return = 0;;
		

		if(!write) {
			_return |= 1 << 5;
		}else {
			_return |= 1;
		}
		
		if(intense) {
			_return |= 1 << 3;
		}
		
		if(hidden) {
			_return |= 1 << 3;
			_return |= 1 << 2;
		}
		
		
		_return = Util.codes[_return];
		
		return _return;

	}
		

}
