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
package org.simplity.kernel.db;

import org.simplity.kernel.ApplicationError;
import org.simplity.kernel.comp.Component;
import org.simplity.kernel.comp.ComponentManager;
import org.simplity.kernel.comp.ComponentType;
import org.simplity.kernel.comp.ValidationContext;
import org.simplity.kernel.data.DataSheet;
import org.simplity.kernel.data.FieldsInterface;
import org.simplity.kernel.dm.Record;
import org.simplity.service.ServiceContext;

/**
 * A stored procedure that can be invoked from app layer.
 *
 * @author simplity.org
 *
 */
public class StoredProcedure implements Component {
	private static final ComponentType MY_TYPE = ComponentType.SP;
	/**
	 * unique within a module. this is the name of this component, and not the
	 * actual name of procedure in the RDBMS
	 */
	String name;

	/**
	 * module + name is unique
	 */
	String moduleName;

	/**
	 * actual name of the procedure, using which it can be invoked
	 */
	String procedureName;

	/**
	 * parameters in the same order as in the actual definition. Note that their
	 * name is meant to pick up values, and need not match the name as in db
	 * design.
	 */
	ProcedureParameter[] parameters;

	/**
	 * if you are going to use the returned value, then it should be the first
	 * in in parameters list
	 */
	boolean firstParameterIsForReturnedValue;

	/**
	 * output records, in case you are returning one or more record sets from
	 * this stored procedure
	 */
	String[] outputRecordNames;
	/**
	 * we need the pseudo prepared statement for this store procedure of the
	 * form {call ProcedureName(?,?,...)}
	 */
	private String sql;

	/**
	 *
	 * @return unqualified name
	 */
	@Override
	public String getSimpleName() {
		return this.name;
	}

	/**
	 *
	 * @return fully qualified name typically module.name
	 */
	@Override
	public String getQualifiedName() {
		return this.moduleName + '.' + this.name;
	}

	/**
	 *
	 * @param inSheet
	 * @param outSheet
	 * @param driver
	 * @param ctx
	 * @return outputSheets
	 */
	public DataSheet[] execute(FieldsInterface inSheet,
			FieldsInterface outSheet, DbDriver driver, ServiceContext ctx) {
		DataSheet[] outSheets = null;
		int nbrSheets = this.getNbrOutputRecords();
		if (nbrSheets > 0) {
			outSheets = new DataSheet[nbrSheets];
			for (int i = 0; i < nbrSheets; i++) {
				Record record = ComponentManager
						.getRecord(this.outputRecordNames[i]);
				outSheets[i] = record.createSheet(false, false);
			}
		}
		driver.executeSp(this.sql, inSheet, outSheet, this.parameters,
				outSheets, ctx);
		return outSheets;
	}

	@Override
	public void getReady() {
		int totalParms = 0;
		if (this.parameters != null) {
			totalParms = this.parameters.length;
			int i = 0;
			for (ProcedureParameter p : this.parameters) {
				i++;
				p.getReady(i);
			}
		}
		StringBuilder sbf = new StringBuilder("{");
		if (this.firstParameterIsForReturnedValue) {
			if (this.parameters == null) {
				throw new ApplicationError(
						"Stored Procedure "
								+ this.getQualifiedName()
								+ " sets firstParameterIsForReturnedValue=true but no parameters are specified. Ensure that first parameter is defined as an output parameter");
			}
			if (this.parameters[0].inOutType != InOutType.OUTPUT) {
				throw new ApplicationError(
						"Stored Procedure "
								+ this.getQualifiedName()
								+ " sets firstParameterIsForReturnedValue=true but the first parameter is not of type OUTPUT");
			}
			sbf.append("? = ");
			totalParms--;
		}
		sbf.append("call ");
		sbf.append(this.procedureName).append('(');
		if (totalParms > 0) {
			sbf.append('?');
			while (--totalParms > 0) {
				sbf.append(",?");
			}
		}
		sbf.append(")}");
		this.sql = sbf.toString();

	}

	/**
	 * @return number of result sets that this sp returns
	 */
	public int getNbrOutputRecords() {
		if (this.outputRecordNames != null) {
			return this.outputRecordNames.length;
		}
		return 0;
	}

	/**
	 * @return sheet names based on default sheet names of output records
	 */
	public String[] getDefaultSheetNames() {
		String[] names = null;
		int nbrSheets = this.getNbrOutputRecords();
		if (nbrSheets > 0) {
			names = new String[nbrSheets];
			for (int i = 0; i < nbrSheets; i++) {
				Record record = ComponentManager
						.getRecord(this.outputRecordNames[i]);
				names[i] = record.getDefaultSheetName();
			}
		}
		return names;
	}

	@Override
	public int validate(ValidationContext ctx) {
		ctx.beginValidation(MY_TYPE, this.getQualifiedName());
		try{
		int count = 0;
		if (this.procedureName != null) {
			ctx.addError("Procedure name is required.");
			count++;
		}

		if (this.outputRecordNames != null) {
			for (String rec : this.outputRecordNames) {
				count += ctx
						.checkRecordExistence(rec, "outputRecordName", true);
			}
		}

		if (this.parameters != null) {
			for (ProcedureParameter param : this.parameters) {
				count += param.validate(ctx);
			}
		}
		return count;
		}finally{
			ctx.endValidation();
		}
	}

	@Override
	public ComponentType getComponentType() {
		return MY_TYPE;
	}
}
