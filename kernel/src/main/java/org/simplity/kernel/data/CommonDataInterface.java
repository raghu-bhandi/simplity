/*
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
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.simplity.kernel.data;

/**
 * Default generic data structure that can be used as memory organization for
 * implementing any logic. for example implementing a service. created for
 * implementing a service.
 *
 * A common data contains fields and sheets. A column of a sheet can be used as
 * a field by using a naming convention tableName:columnName as field name.
 * Value of column from the only row, or the current row of a sheet is used.
 *
 * we do not provide any syntax to specify row number. For example
 * tableName:columnName:idx. Instead, we provide a feature to iterate on a
 * sheet, in which case we change the current row internally. As of now, we
 * believe setting currentRow is not a good API. However, we will get involved
 * in actual usage of these API's and re-factor/redesign as when needed
 *
 * @author simplity.org
 *
 */
public interface CommonDataInterface extends FieldsInterface {

	/**
	 * get sheet, or null if no such field
	 *
	 * @param sheetName
	 * 		sheetName
	 * @return sheet or null if sheet does not exist
	 */
	public DataSheet getDataSheet(String sheetName);

	/**
	 * add/replace a data sheet.
	 *
	 * @param sheetName
	 *            not null
	 * @param sheet
	 *            not null, but vaule.isNull() could be true
	 */
	public void putDataSheet(String sheetName, DataSheet sheet);

	/**
	 * do we have this data sheet?
	 *
	 * @param sheetName
	 * 			sheetName
	 * @return true if sheet exists. False otherwise.
	 */
	public boolean hasDataSheet(String sheetName);

	/**
	 * remove a data sheet
	 *
	 * @param sheetName
	 * 		sheetName
	 * @return existing sheet, or null if no such sheet existed before this
	 *         operation
	 */
	public DataSheet removeDataSheet(String sheetName);

	/**
	 * get an iterator on this sheet. Only one iterator could be active at any
	 * point. Caller has to carefully design iteration to avoid nested iteration
	 * on the same sheet. Also, caller must ensure that the iteration is
	 * cancelled in case the iteration is not completed. Run time error is
	 * thrown on any attempt start iteration on a sheet that is already in the
	 * middle of an iteration.
	 *
	 * @param sheetName
	 * 			sheetName
	 * @return iterator instance for looping on each row of this sheet
	 * @throws AlreadyIteratingException
	 *             if this sheet is in the middle of an iteration (earlier one
	 *             is neither completed, not cancelled)
	 */
	public DataSheetIterator startIteration(String sheetName) throws AlreadyIteratingException;

}
