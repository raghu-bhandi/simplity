/*
 * Copyright (c) 2015 EXILANT Technologies Private Limited (www.exilant.com)
 * Copyright (c) 2016 simplity.org
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.simplity.tp;

import org.simplity.kernel.Tracer;
import org.simplity.kernel.data.DataSheet;
import org.simplity.kernel.db.DbDriver;
import org.simplity.kernel.value.Value;
import org.simplity.service.ServiceContext;

/**
 * Rename a data sheet. Returns true if renaming is successful, false otherwise
 *
 *
 * @author simplity.org
 *
 */
public class RenameSheet extends Action {

	/**
	 * current name
	 */
	String sheetName;

	/**
	 * new name
	 */
	String newSheetName;

	/*
	 * true if sheet got renamed. false otherwise. No error raised even if sheet
	 * is missing
	 */
	@Override
	protected Value doAct(ServiceContext ctx, DbDriver driver) {
		DataSheet sheet = ctx.removeDataSheet(this.sheetName);
		if (sheet == null) {
			Tracer.trace("Data sheet " + this.sheetName
					+ " not found, and hence is not renamed to "
					+ this.newSheetName);
			return Value.VALUE_FALSE;
		}
		ctx.putDataSheet(this.newSheetName, sheet);
		return Value.VALUE_TRUE;

	}
}
