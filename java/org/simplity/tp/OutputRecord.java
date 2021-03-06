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
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.simplity.tp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.simplity.json.JSONWriter;
import org.simplity.kernel.ApplicationError;
import org.simplity.kernel.Tracer;
import org.simplity.kernel.comp.ComponentManager;
import org.simplity.kernel.comp.ValidationContext;
import org.simplity.kernel.data.DataSheet;
import org.simplity.kernel.data.HierarchicalSheet;
import org.simplity.kernel.dm.Field;
import org.simplity.kernel.dm.Record;
import org.simplity.kernel.util.JsonUtil;
import org.simplity.kernel.value.Value;
import org.simplity.service.ServiceContext;

/**
 * represents a record/row/table that is used as input/output of a service
 *
 */
public class OutputRecord {

	/**
	 * sheet into which output should get into. If null, then recordName should
	 * be specified are output
	 */
	String sheetName;
	/**
	 * If you want all fields form a record to be out put, this is a convenient,
	 * rather than listing all fields in fields list
	 */
	String recordName;

	/**
	 * if this sheet has child rows for a parent, we have to output hierarchical
	 * data.
	 */
	String parentSheetName;
	/**
	 * and we need to know how to identify child rows for a parent row. we use
	 * common columns in the two sheets to link them
	 */
	String linkColumnInThisSheet;

	String linkColumnInParentSheet;

	/*
	 * we cache child records for convenience
	 */
	private OutputRecord[] childRecords;

	private Field[] fields;
	/**
	 * is the associated record a complex struct?
	 */
	private boolean isComplexStruct;
	/**
	 * keep a pointer to parent in case we need to delegate some work back
	 */
	private OutputData myParenData;

	/**
	 * default constructor
	 */
	public OutputRecord() {
		// default
	}

	/**
	 * create an output record for a record
	 *
	 * @param record
	 *
	 */
	public OutputRecord(Record record) {
		this.recordName = record.getQualifiedName();
		this.sheetName = record.getDefaultSheetName();
	}

	/**
	 * create output record for a child sheet
	 *
	 * @param sheetName
	 * @param parentSheetName
	 * @param childColName
	 * @param parentColName
	 */
	public OutputRecord(String sheetName, String parentSheetName,
			String childColName, String parentColName) {
		this.sheetName = sheetName;
		this.parentSheetName = parentSheetName;
		this.linkColumnInThisSheet = childColName;
		this.linkColumnInParentSheet = parentColName;
	}

	/**
	 * set child records
	 *
	 * @param children
	 *            array that may have nulls at the end
	 * @param nbrChildren
	 *            number of non-null entries in this
	 */
	void setChildren(OutputRecord[] children, int nbrChildren) {
		if (nbrChildren == children.length) {
			this.childRecords = children;
		} else {
			OutputRecord[] ch = new OutputRecord[nbrChildren];
			for (int i = 0; i < nbrChildren; i++) {
				ch[i] = children[i];
			}
			this.childRecords = ch;
		}
	}

	/**
	 * @param writer
	 * @param ctx
	 */
	public void toJson(JSONWriter writer, ServiceContext ctx) {
		if (this.isComplexStruct) {
			Object obj = ctx.getObject(this.sheetName);
			writer.key(this.sheetName);
			if (obj == null) {
				Tracer.trace("No Object found for complex structure "
						+ this.sheetName + ". Null sent to client");
			}
			writer.value(obj);
			return;
		}
		if (this.sheetName == null) {
			this.fieldsToJson(writer, this.fields, ctx);
			return;
		}
		/*
		 * is this a child sheet?
		 */
		if (this.parentSheetName != null) {
			Tracer.trace("Sheet " + this.sheetName
					+ " will be output as part of its parent sheet "
					+ this.parentSheetName);
			return;
		}
		this.sheetToJson(writer, ctx);
	}

	private void fieldsToJson(JSONWriter writer, Field[] fieldsToOutput,
			ServiceContext ctx) {
		for (Field field : fieldsToOutput) {
			String fieldName = field.getName();
			Value value = ctx.getValue(fieldName);
			if (value == null) {
				Tracer.trace(fieldName
						+ " has no value and hence is not added to output");
			} else {
				writer.key(fieldName).value(value.toObject());
			}
		}
	}

	private void sheetToJson(JSONWriter writer, ServiceContext ctx) {
		/*
		 * try and output sheet
		 */
		DataSheet sheet = ctx.getDataSheet(this.sheetName);
		if (sheet == null) {
			if (this.fields != null) {
				if (this.myParenData.okToOutputFieldsFromRecord(this.fields)) {
					Tracer.trace("Service context has no sheet with name "
							+ this.sheetName
							+ " for output. We try and output fields.");
					this.fieldsToJson(writer, this.fields, ctx);
				} else {
					Tracer.trace("Service context has no sheet with name "
							+ this.sheetName
							+ " for output. We decided against trying fields collection because it will create duplicate keys in response.");
				}
			}
			/*
			 * and we push the child records as if they have no parent.
			 */
			if (this.childRecords != null) {
				for (OutputRecord child : this.childRecords) {
					child.sheetToJson(writer, ctx);
				}
			}
			return;
		}

		HierarchicalSheet[] children = null;
		if (this.childRecords != null) {
			/*
			 * we have to output hierarchical data
			 */
			children = new HierarchicalSheet[this.childRecords.length];
			int i = 0;
			for (OutputRecord child : this.childRecords) {
				children[i++] = child.getHierarchicalSheet(sheet, ctx);
			}
		}
		writer.key(this.sheetName);
		JsonUtil.sheetToJson(writer, sheet, children);
	}

	/**
	 *
	 * @param parentSheet
	 * @param ctx
	 * @return create a hierarchical sheet for this outputRecord for this
	 *         service context
	 */
	public HierarchicalSheet getHierarchicalSheet(DataSheet parentSheet,
			ServiceContext ctx) {
		DataSheet mySheet = ctx.getDataSheet(this.sheetName);
		if (mySheet == null) {
			Tracer.trace("Sheet " + this.sheetName + " has no data to output");
			return null;
		}
		/*
		 * get field names for this sheet
		 */
		String[] fieldNames = mySheet.getColumnNames();

		/*
		 * get data grouped and indexed by link key value
		 */
		int myIdx = mySheet.getColIdx(this.linkColumnInThisSheet);
		if (myIdx == -1) {
			throw new ApplicationError(this.linkColumnInThisSheet
					+ " is not a valid column in sheet " + this.sheetName);
		}
		Map<String, List<Value[]>> map = new HashMap<String, List<Value[]>>();
		for (Value[] row : mySheet.getAllRows()) {
			String key = row[myIdx].toString();
			List<Value[]> rows = map.get(key);
			if (rows == null) {
				rows = new ArrayList<Value[]>();
				map.put(key, rows);
			}
			rows.add(row);
		}
		int parentIdx = parentSheet.getColIdx(this.linkColumnInParentSheet);
		if (parentIdx == -1) {
			throw new ApplicationError(
					"Link column " + this.linkColumnInParentSheet
							+ " is not found in parent sheet.");
		}
		/*
		 * is this child a parent?
		 */
		HierarchicalSheet[] children = null;
		if (this.childRecords != null) {
			children = new HierarchicalSheet[this.childRecords.length];
			int i = 0;
			for (OutputRecord child : this.childRecords) {
				children[i++] = child.getHierarchicalSheet(mySheet, ctx);
			}
		}
		return new HierarchicalSheet(this.sheetName, fieldNames, map,
				parentIdx, children);
	}

	/**
	 * open shop and get ready for service
	 *
	 * @param parenData
	 */
	public void getReady(OutputData parenData) {
		this.myParenData = parenData;
		if (this.recordName == null) {
			if (this.sheetName == null) {
				throw new ApplicationError(
						"Output record should have either sheet name or record name specified.");
			}
		} else {
			Record record = ComponentManager.getRecord(this.recordName);
			if (record.isComplexStruct()) {
				this.isComplexStruct = true;
			} else {
				this.fields = record.getFields();
				if (this.fields == null) {
					Tracer.trace("Record " + this.recordName
							+ " yielded no fields");
				}
			}
		}
	}

	/**
	 * @param ctx
	 * @return
	 */
	int validate(ValidationContext ctx) {
		return 0;
	}
}
