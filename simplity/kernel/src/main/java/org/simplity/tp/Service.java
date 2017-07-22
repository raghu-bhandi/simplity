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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.transaction.UserTransaction;

import org.simplity.jms.JmsConnector;
import org.simplity.jms.JmsUsage;
import org.simplity.json.JSONArray;
import org.simplity.json.JSONObject;
import org.simplity.kernel.Application;
import org.simplity.kernel.ApplicationError;
import org.simplity.kernel.FormattedMessage;
import org.simplity.kernel.MessageBox;
import org.simplity.kernel.MessageType;
import org.simplity.kernel.Messages;
import org.simplity.kernel.comp.ComponentManager;
import org.simplity.kernel.comp.ComponentType;
import org.simplity.kernel.comp.ValidationContext;
import org.simplity.kernel.data.DataPurpose;
import org.simplity.kernel.data.DataSheet;
import org.simplity.kernel.db.DbAccessType;
import org.simplity.kernel.db.DbDriver;
import org.simplity.kernel.dm.Record;
import org.simplity.kernel.dt.DataType;
import org.simplity.kernel.util.TextUtil;
import org.simplity.kernel.value.Value;
import org.simplity.service.InputData;
import org.simplity.service.InputField;
import org.simplity.service.InputRecord;
import org.simplity.service.OutputData;
import org.simplity.service.OutputRecord;
import org.simplity.service.PayloadType;
import org.simplity.service.ServiceContext;
import org.simplity.service.ServiceData;
import org.simplity.service.ServiceInterface;
import org.simplity.service.ServiceProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction Processing Service
 *
 * @author simplity.org
 */
public class Service implements ServiceInterface {

	static final Logger logger = LoggerFactory.getLogger(Service.class);

	/*
	 * constants used by on-the-fly services
	 */
	private static final char PREFIX_DELIMITER = '_';
	private static final String GET = "get";
	private static final String FILTER = "filter";
	private static final String SAVE = "save";
	/** used by suggestion service as well */
	public static final String SUGGEST = "suggest";

	/** list service needs to use this */
	private static final String LIST = "list";

	/** stop the execution of this service as success */
	public static final Value STOP_VALUE = Value.newTextValue("_s");
	/**
	 * field name with which result of an action is available in service context
	 */
	public static final String RESULT_SUFFIX = "Result";

	private static final ComponentType MY_TYPE = ComponentType.SERVICE;

	/** simple name */
	String name;

	/** module name.simpleName would be fully qualified name. */
	String moduleName;

	/**
	 * if this is implemented as a java code. If this is specified, no attribute
	 * (other than name and module name) are relevant
	 */
	String className;

	/** database access type */
	DbAccessType dbAccessType = DbAccessType.NONE;

	/**
	 * input fields/grids for this service. not valid if requestTextFieldName is
	 * specified
	 */
	InputData inputData;

	/** copy input records from another service */
	String referredServiceForInput;
	/** copy output records from another service */
	String referredServiceForOutput;

	/**
	 * schema name, different from the default schema, to be used specifically
	 * for this service
	 */
	String schemaName;
	/**
	 * output fields and grids for this service. Not valid if
	 * responseTextFieldName is specified
	 */
	OutputData outputData;

	/** actions that make up this service */
	Action[] actions;

	/** should this be executed in the background ALWAYS?. */
	boolean executeInBackground;

	/**
	 * can the response from this service be cached. If this is false, other
	 * caching related attributes are irrelevant.
	 */
	boolean okToCache;

	/**
	 * Should this response cache be discarded after certain time? 0 means no
	 * such predetermined validity
	 */
	int cacheValidityInMinutes;

	/**
	 * valid only if okToCache is set to true. Use this if the service response
	 * can be cached for the same values for this list of fields. For example if
	 * getDistricts is a service that responds with all districts for the given
	 * state in the given countryCode then "countryCode,stateCode" is the value
	 * of this attribute.
	 *
	 * If the response is specific to given user, then _userId should be the
	 * first field in the list.
	 * Skip this if the response is not dependent on any input
	 */
	String[] cacheKeyNames;

	/**
	 * The cached response for services that are to be invalidated when this
	 * service is executed. for example updateStates service would invalidate
	 * cached responses from getStates service
	 */
	String[] serviceCachesToInvalidate;

	/**
	 * does this service use jms? if so with what kind of transaction management
	 */
	JmsUsage jmsUsage;

	/** action names indexed to respond to navigation requests */
	private final HashMap<String, Integer> indexedActions = new HashMap<String, Integer>();

	/** flag to avoid repeated getReady() calls */
	private boolean gotReady;

	/** instance of className to be used as body of this service */
	private ServiceInterface serviceInstance;

	/**
	 * key names for services that are to be invalidated
	 */
	private String[][] invalidationKeys;

	@Override
	public DbAccessType getDataAccessType() {
		return this.dbAccessType;
	}

	@Override
	public String getSimpleName() {
		return this.name;
	}

	@Override
	public boolean toBeRunInBackground() {
		return this.executeInBackground;
	}

	@Override
	public boolean okToCache() {
		return this.okToCache;
	}

	@Override
	public String getQualifiedName() {
		if (this.moduleName == null) {
			return this.name;
		}
		return this.moduleName + '.' + this.name;
	}

	@Override
	public ServiceData respond(ServiceData inData, PayloadType payloadType) {
		if (this.serviceInstance != null) {
			return this.serviceInstance.respond(inData, payloadType);
		}

		ServiceContext ctx = new ServiceContext(this.name, inData.getUserId());

		/*
		 * copy values and data sheets sent by the client agent. These are
		 * typically session-stored, but not necessarily that
		 */
		for (String key : inData.getFieldNames()) {
			Object val = inData.get(key);
			if (val instanceof Value) {
				ctx.setValue(key, (Value) val);
			} else if (val instanceof DataSheet) {
				ctx.putDataSheet(key, (DataSheet) val);
			} else {
				ctx.setObject(key, val);
			}
		}
		MessageBox box = inData.getMessageBox();
		if (box != null) {
			ctx.setMessageBox(box);
		}
		/*
		 * process input specification
		 */
		if (payloadType != PayloadType.NONE) {
			this.extractInput(ctx, inData.getPayLoad(), payloadType);
		}

		/*
		 * if input is in error, we return to caller without processing this
		 * service
		 */
		if (ctx.isInError()) {
			return this.prepareResponse(ctx, payloadType);
		}

		if (this.outputData != null) {
			this.outputData.onServiceStart(ctx);
		}

		/*
		 * our service context is ready to execute this service now
		 */
		ApplicationError exception = this.executeService(ctx);

		if (exception != null) {
			throw exception;
		}

		return this.prepareResponse(ctx, payloadType);
	}

	/**
	 * execute this service carefully managing the resources. Ensure that there
	 * is no leakage
	 *
	 * @param ctx
	 *            service context
	 * @return application error if the service generated one. null is all ok
	 */
	private ApplicationError executeService(ServiceContext ctx) {
		/*
		 * resources that need to be released without fail..
		 */
		JmsConnector jmsConnector = null;
		UserTransaction userTransaciton = null;

		ApplicationError exception = null;
		BlockWorker worker = new BlockWorker(this.actions, this.indexedActions, ctx);
		/*
		 * execute all actions
		 */
		try {
			/*
			 * acquire resources that are needed for this service
			 */
			if (this.jmsUsage != null) {
				jmsConnector = JmsConnector.borrowConnector(this.jmsUsage);
				ctx.setJmsSession(jmsConnector.getSession());
			}

			DbAccessType access = this.dbAccessType;
			/*
			 * is this a JTA transaction?
			 */
			if (access == DbAccessType.EXTERNAL) {
				userTransaciton = Application.getUserTransaction();
				userTransaciton.begin();
			}
			if (access == DbAccessType.NONE) {
				worker.act(null);
			} else {
				/*
				 * Also, sub-Service means we open a read-only, and then call
				 * sub-service and complex-logic to manage their own connections
				 */
				if (access == DbAccessType.SUB_SERVICE) {
					access = DbAccessType.READ_ONLY;
				}
				DbDriver.workWithDriver(worker, access, this.schemaName);
			}
		} catch (ApplicationError e) {
			exception = e;
		} catch (Exception e) {
			exception = new ApplicationError(e, "Exception during execution of service. ");
		}
		/*
		 * close/return resources
		 */
		if (jmsConnector != null) {
			JmsConnector.returnConnector(jmsConnector, exception == null && ctx.isInError() == false);
		}
		if (userTransaciton != null) {
			try {
				if (exception == null && ctx.isInError() == false) {
					userTransaciton.commit();
				} else {

					logger.info("Service is in error. User transaction rolled-back");

					userTransaciton.rollback();
				}
			} catch (Exception e) {
				exception = new ApplicationError(e, "Error while commit/rollback of user transaction");
			}
		}
		return exception;
	}

	/**
	 * prepare service data to be returned to caller based on the content of
	 * service context
	 *
	 * @param ctx
	 * @param payloadType
	 * @return
	 */
	private ServiceData prepareResponse(ServiceContext ctx, PayloadType payloadType) {
		ServiceData response = new ServiceData(ctx.getUserId(), this.getQualifiedName());
		int nbrErrors = 0;
		for (FormattedMessage msg : ctx.getMessages()) {
			if (msg.messageType == MessageType.ERROR) {
				nbrErrors++;
				response.addMessage(msg);
			}
		}
		/*
		 * create output pay load, but only if service succeeded. Dirty job of
		 * telling the bad news is left to the Client Agent :-)
		 */
		if (nbrErrors == 0) {
			this.prepareResponse(ctx, response, payloadType);
			if (this.inputData != null) {
				this.inputData.cleanup(ctx);
			}
			if (this.cacheKeyNames != null) {
				response.setCacheForInput(this.cacheKeyNames);
			}
		}

		return response;
	}

	/**
	 * @param ctx
	 * @param requestText
	 * @param paylodType
	 */
	protected void extractInput(ServiceContext ctx, Object payload, PayloadType payloadType) {
		if (payload == null) {

			logger.info("No input received from client");

			return;
		}
		if (this.inputData == null) {

			logger.info("We received input data, but this service is designed not to make use of input.");

			return;
		}
		try {
			if (payloadType == PayloadType.JSON) {
				this.inputData.extractFromJson(payload.toString(), ctx);
			} else {
				this.inputData.extractFromJson((JSONObject) payload, ctx);
			}

		} catch (Exception e) {
			ctx.addMessage(Messages.INVALID_DATA, "Invalid input data format. " + e.getMessage());
		}
	}

	/**
	 * copy desired output to response
	 *
	 * @param ctx
	 * @param response
	 * @param payloadType
	 */
	protected void prepareResponse(ServiceContext ctx, ServiceData response, PayloadType payloadType) {

		if (payloadType == null || payloadType == PayloadType.NONE) {
			/*
			 * we are not to worry about payload. Let us copy everything to
			 * output
			 */
			this.copyEverything(ctx, response);
			return;
		}
		if (this.outputData == null) {

			logger.info("Service " + this.name + " is designed to send no response.");

			return;
		}
		this.outputData.setResponse(ctx, response, payloadType);
	}

	private void copyEverything(ServiceContext ctx, ServiceData response) {
		/*
		 * copy every thing from ctx to service data
		 */
		for (Map.Entry<String, Value> entry : ctx.getAllFields()) {
			response.put(entry.getKey(), entry.getValue());
		}
		for (Map.Entry<String, DataSheet> entry : ctx.getAllSheets()) {
			Object obj = response.put(entry.getKey(), entry.getValue());
			if (obj != null) {

				logger.info("Warning: " + entry.getKey()
						+ " is used as a field nae as well as data sheet name in service context. field value is ignored and only data sheet is copied to output servie data.");
			}
		}
	}

	private boolean canWorkWithDriver(DbDriver driver) {
		/*
		 * use of JMS may trigger this irrespective of db access
		 */
		if (this.jmsUsage == JmsUsage.SERVICE_MANAGED || this.jmsUsage == JmsUsage.EXTERNALLY_MANAGED) {
			return false;
		}
		/*
		 * if we do not need it all, anything will do..
		 */
		if (this.dbAccessType == null || this.dbAccessType == DbAccessType.NONE) {
			return true;
		}
		/*
		 * can not work with null.
		 */
		if (driver == null) {
			return false;
		}

		/*
		 * may be we can get away for reads
		 */
		if (this.dbAccessType == DbAccessType.READ_ONLY) {
			if (this.schemaName == null || this.schemaName.equalsIgnoreCase(driver.getSchema())) {
				return true;
			}
		}

		/*
		 * we tried our best to re-use... but failed
		 */
		return false;
	}

	@Override
	public Value executeAsAction(ServiceContext ctx, DbDriver driver, boolean transactionIsDelegated) {
		/*
		 * are we to manage our own transaction?
		 */
		if (transactionIsDelegated) {
			if (this.canWorkWithDriver(driver) == false) {
				/*
				 * execute this as a service
				 */
				ApplicationError err = this.executeService(ctx);
				if (err != null) {
					throw err;
				}
				return Value.VALUE_TRUE;
			}
		}

		/*
		 * is this a custom service?
		 */
		if (this.serviceInstance != null) {
			return this.serviceInstance.executeAsAction(ctx, driver, transactionIsDelegated);
		}
		/*
		 * this is a simple action
		 */
		BlockWorker worker = new BlockWorker(this.actions, this.indexedActions, ctx);
		boolean result = worker.workWithDriver(driver);
		if (result) {
			return Value.VALUE_TRUE;
		}
		return Value.VALUE_FALSE;
	}

	@Override
	public void getReady() {
		if (this.gotReady) {

			logger.info("Service " + this.getQualifiedName()
					+ " is being harassed by repeatedly asking it to getReady(). Please look into this..");

			return;
		}
		this.gotReady = true;
		if (this.className != null) {
			try {
				this.serviceInstance = Application.getBean(this.className, ServiceInterface.class);
			} catch (Exception e) {
				throw new ApplicationError(e,
						"Unable to get an instance of service using class name " + this.className);
			}
		}
		if (this.actions == null) {

			logger.info("Service " + this.getQualifiedName() + " has no actions.");

			this.actions = new Action[0];
		} else {
			this.prepareChildren();
		}
		/*
		 * input record may have to be copied form referred service
		 */
		if (this.referredServiceForInput != null) {
			if (this.inputData != null) {
				throw new ApplicationError("Service " + this.getQualifiedName() + " refers to service "
						+ this.referredServiceForInput + " but also specifies its own input records.");
			}
			ServiceInterface service = ComponentManager.getService(this.referredServiceForInput);
			if (service instanceof Service == false) {
				throw new ApplicationError("Service " + this.getQualifiedName() + " refers to another service "
						+ this.referredServiceForInput + ", but that is not an xml-based service.");
			}
			this.inputData = ((Service) service).inputData;
		}
		if (this.inputData != null) {
			this.inputData.getReady();
		}
		/*
		 * output record may have to be copied form referred service
		 */
		if (this.referredServiceForOutput != null) {
			if (this.outputData != null) {
				throw new ApplicationError("Service " + this.getQualifiedName() + " refers to service "
						+ this.referredServiceForOutput + " but also specifies its own output records.");
			}
			ServiceInterface service = ComponentManager.getService(this.referredServiceForOutput);
			if (service instanceof Service == false) {
				throw new ApplicationError("Service " + this.getQualifiedName() + " refers to another service "
						+ this.referredServiceForOutput + ", but that is not an xml-based service.");
			}
			this.outputData = ((Service) service).outputData;
		}
		if (this.outputData != null) {
			this.outputData.getReady();
		}

		if (this.serviceCachesToInvalidate != null) {
			this.invalidationKeys = new String[this.serviceCachesToInvalidate.length][];
			for (int i = 0; i < this.invalidationKeys.length; i++) {
				ServiceInterface service = ComponentManager.getService(this.serviceCachesToInvalidate[i]);
				this.invalidationKeys[i] = service.getCacheKeyNames();
			}
		}
	}

	private void prepareChildren() {
		int i = 0;
		boolean delegated = this.dbAccessType == DbAccessType.SUB_SERVICE;
		for (Action action : this.actions) {
			action.getReady(i, this);
			if (this.indexedActions.containsKey(action.actionName)) {
				throw new ApplicationError("Service " + this.name + " has duplicate action name " + action.actionName
						+ " as its action nbr " + (i + 1));
			}
			this.indexedActions.put(action.getName(), new Integer(i));
			i++;
			/*
			 * programmers routinely forget to set the dbaccess type.. we think
			 * it is worth this run-time over-head to validate it again
			 */
			if (delegated && (action instanceof SubService)) {
				continue;
			}

			if (this.dbAccessType.canWorkWithChildType(action.getDataAccessType()) == false) {
				throw new ApplicationError(
						"Service " + this.getQualifiedName() + " uses dbAccessTYpe=" + this.dbAccessType
								+ " but action " + action.getName() + " requires " + action.getDataAccessType());
			}
		}
	}

	/**
	 * @param serviceName
	 * @param record
	 * @return a service that is designed to read a row for the primary key from
	 *         the table associated with this record
	 */
	public static Service getReadService(String serviceName, Record record) {
		String recordName = record.getQualifiedName();
		Service service = new Service();
		service.dbAccessType = DbAccessType.READ_ONLY;
		service.setName(serviceName);
		service.schemaName = record.getSchemaName();

		/*
		 * what is to be input
		 */
		InputRecord inRec = new InputRecord();
		inRec.setRecordName(recordName);
		inRec.setPurpose(DataPurpose.READ);
		InputRecord[] inRecs = { inRec };
		InputData inData = new InputData();
		inData.setRecords(inRecs);
		service.inputData = inData;

		/*
		 * We have just one action : read action
		 */
		Action action = new Read(record);
		action.failureMessageName = Messages.NO_ROWS;

		Action[] actions = { action };
		service.actions = actions;

		/*
		 * output fields from record.
		 */
		OutputRecord[] outRecs = getOutputRecords(record);
		OutputData outData = new OutputData();
		outData.setOutputRecords(outRecs);
		service.outputData = outData;

		return service;
	}

	/**
	 * @param serviceName
	 * @param record
	 * @return service that filter rows from table associated with this record,
	 *         and possibly reads related rows from child records
	 */
	public static Service getFilterService(String serviceName, Record record) {
		String recordName = record.getQualifiedName();
		Service service = new Service();
		service.dbAccessType = DbAccessType.READ_ONLY;
		service.setName(serviceName);
		service.schemaName = record.getSchemaName();

		/*
		 * input for filter
		 */
		InputRecord inRec = new InputRecord();
		inRec.setRecordName(recordName);
		inRec.setPurpose(DataPurpose.FILTER);
		InputRecord[] inRecs = { inRec };
		InputData inData = new InputData();
		inData.setRecords(inRecs);
		service.inputData = inData;

		Action action;
		OutputData outData = new OutputData();
		service.outputData = outData;
		/*
		 * if we have to read children, we use filter action, else we use
		 * filterToJson
		 */
		if (record.getChildrenToOutput() == null) {
			action = new FilterToJson(record);
			outData.enableOutputFromWriter();
		} else {
			action = new Filter(record);
			OutputRecord[] outRecs = getOutputRecords(record);
			outData.setOutputRecords(outRecs);
		}
		action.failureMessageName = Messages.NO_ROWS;
		Action[] actions = { action };
		service.actions = actions;

		/*
		 * getReady() is called by component manager any ways..
		 */
		return service;
	}

	/**
	 * @param serviceName
	 * @param record
	 * @return service that returns a sheet with suggested rows for the supplied
	 *         text value
	 */
	public static Service getSuggestionService(String serviceName, Record record) {
		Service service = new Service();
		service.dbAccessType = DbAccessType.READ_ONLY;
		service.setName(serviceName);
		service.schemaName = record.getSchemaName();

		/*
		 * input for suggest
		 */
		InputField f1 = new InputField(ServiceProtocol.LIST_SERVICE_KEY, DataType.DEFAULT_TEXT, true, null);
		InputField f2 = new InputField(ServiceProtocol.SUGGEST_STARTING, DataType.DEFAULT_BOOLEAN, false, null);

		InputField[] inFields = { f1, f2 };
		InputData inData = new InputData();
		inData.setInputFields(inFields);
		service.inputData = inData;

		/*
		 * use a suggest action to do the job
		 */
		Action action = new Suggest(record);
		action.failureMessageName = Messages.NO_ROWS;
		Action[] actions = { action };
		service.actions = actions;

		/*
		 * output as sheet
		 */
		OutputRecord outRec = new OutputRecord(record);
		OutputRecord[] outRecs = { outRec };
		OutputData outData = new OutputData();
		outData.setOutputRecords(outRecs);
		service.outputData = outData;

		/*
		 * getReady() is called by component manager any ways..
		 */
		return service;
	}

	/**
	 * @param serviceName
	 * @param record
	 * @return service that returns a sheet with suggested rows for the supplied
	 *         text value
	 */
	public static Service getListService(String serviceName, Record record) {
		Service service = new Service();
		service.dbAccessType = DbAccessType.READ_ONLY;
		service.setName(serviceName);
		service.schemaName = record.getSchemaName();
		if (record.getOkToCache()) {
			String keyName = record.getValueListKeyName();
			if (keyName == null) {
				keyName = "";
			}
			String[] list = { keyName };
			service.cacheKeyNames = list;
		}

		/*
		 * do we need any input? we are flexible
		 */

		InputField f1 = new InputField(ServiceProtocol.LIST_SERVICE_KEY, DataType.DEFAULT_TEXT, false, null);
		InputField[] inFields = { f1 };
		InputData inData = new InputData();
		inData.setInputFields(inFields);
		service.inputData = inData;
		/*
		 * use a List action to do the job
		 */
		Action action = new KeyValueList(record);
		Action[] actions = { action };
		service.actions = actions;

		/*
		 * output as sheet
		 */
		OutputRecord outRec = new OutputRecord(record);
		OutputRecord[] outRecs = { outRec };
		OutputData outData = new OutputData();
		outData.setOutputRecords(outRecs);
		service.outputData = outData;

		/*
		 * getReady() is called by component manager any ways..
		 */
		return service;
	}

	/**
	 * create a service that would save a rows, possibly along with some child
	 * rows
	 *
	 * @param serviceName
	 * @param record
	 * @return a service that would save a rows, possibly along with some child
	 *         rows
	 */
	public static ServiceInterface getSaveService(String serviceName, Record record) {
		String recordName = record.getQualifiedName();
		Service service = new Service();
		service.dbAccessType = DbAccessType.READ_WRITE;
		service.setName(serviceName);
		service.schemaName = record.getSchemaName();

		/*
		 * data for this record is expected in fields, while rows for
		 * child-records in data sheets
		 */
		InputData inData = new InputData();
		inData.setRecords(getInputRecords(record));
		service.inputData = inData;

		/*
		 * save action
		 */
		Save action = new Save(record, getChildRecords(record, false));
		action.failureMessageName = Messages.NO_UPDATE;
		Action[] actions = { action };
		service.actions = actions;
		/*
		 * we think we have to read back the row, but not suer.. Here the action
		 * commented for that
		 */
		// Read action1 = new Read();
		// action1.executeOnCondition = "saveResult != 0";
		// action1.name = "read";
		// action1.recordName = recordName;
		// action1.childRecords = getChildRecords(record, true);
		// Action[] actions = { action, action1 };

		/*
		 * what should we output? We are not sure. As of now let us send back
		 * fields
		 */
		OutputRecord outRec = new OutputRecord();
		outRec.setRecordName(recordName);
		OutputData outData = new OutputData();
		OutputRecord[] outRecs = { outRec };
		outData.setOutputRecords(outRecs);
		service.outputData = outData;

		return service;
	}

	/*
	 * check for name and module name based on the requested name
	 */
	protected void setName(String possiblyQualifiedName) {
		int idx = possiblyQualifiedName.lastIndexOf('.');
		if (idx == -1) {
			this.name = possiblyQualifiedName;
			this.moduleName = null;
		} else {
			this.name = possiblyQualifiedName.substring(idx + 1);
			this.moduleName = possiblyQualifiedName.substring(0, idx);
		}

		logger.info("service name set to " + this.name + " and " + this.moduleName);
	}

	protected static RelatedRecord[] getChildRecords(Record record, boolean forRead) {
		String[] children;
		if (forRead) {
			children = record.getChildrenToOutput();
		} else {
			children = record.getChildrenToInput();
		}
		if (children == null) {
			return null;
		}
		RelatedRecord[] recs = new RelatedRecord[children.length];
		int i = 0;
		for (String child : children) {
			RelatedRecord rr = new RelatedRecord();
			rr.recordName = child;
			rr.sheetName = TextUtil.getSimpleName(child);
			rr.getReady();
			recs[i++] = rr;
		}
		return recs;
	}

	protected static OutputRecord[] getOutputRecords(Record record) {
		List<OutputRecord> recs = new ArrayList<OutputRecord>();
		record.addOutputRecords(recs);
		return recs.toArray(new OutputRecord[0]);
	}

	/**
	 * @param record
	 * @return
	 */
	protected static InputRecord[] getInputRecords(Record record) {
		String recordName = record.getQualifiedName();
		String[] children = record.getChildrenToInput();
		int nrecs = 1;
		if (children != null) {
			nrecs = children.length + 1;
		}

		InputRecord inRec = new InputRecord();
		inRec.setRecordName(recordName);
		inRec.setPurpose(DataPurpose.SAVE);
		inRec.enableSaveAction();
		/*
		 * inputRecord is lenient about sheetName. It is okay to specify that.
		 * In case one row comes from client as fields, inputRecord manages that
		 */
		inRec.setSheetName(record.getDefaultSheetName());
		InputRecord[] recs = new InputRecord[nrecs];
		recs[0] = inRec;
		if (children != null) {
			String sheetName = record.getDefaultSheetName();
			int i = 1;
			for (String child : children) {
				recs[i++] = ComponentManager.getRecord(child).getInputRecord(sheetName);
			}
		}
		return recs;
	}

	@Override
	public int validate(ValidationContext ctx) {
		ctx.beginValidation(MY_TYPE, this.getQualifiedName());
		/*
		 * it is important that we endValidtion() before returning
		 */
		try {
			int count = 0;
			if (this.className != null) {
				try {
					Object obj = Class.forName(this.className).newInstance();
					if (obj instanceof ServiceInterface == false) {
						ctx.addError(
								this.className + " is set as className but it does not implement ServiceInterface");
						count++;
					}
				} catch (Exception e) {
					ctx.addError(this.className + " could not be used to instantiate an object.\n" + e.getMessage());
					count++;
				}
				if (this.actions != null) {
					ctx.addError(this.className
							+ " is set as className. This java code is used as service definition. Actions are not valid.");
					count++;
				}
				return count;
			}
			if (this.actions == null) {
				ctx.addError("No actions.");
				count++;
			} else {
				count += this.validateChildren(ctx);
			}

			if (this.referredServiceForInput != null) {
				if (this.inputData != null) {
					ctx.reportUnusualSetting("referredServiceForInput is used, and hence inputData element is ignored");
				}
				ServiceInterface service = ComponentManager.getServiceOrNull(this.referredServiceForInput);
				if (service == null) {
					ctx.addError("referredServiceForInput set to " + this.referredServiceForInput
							+ " but that service is not defined");
					count++;
				}
			}

			if (this.referredServiceForOutput != null) {
				if (this.outputData != null) {
					ctx.reportUnusualSetting(
							"referredServiceForOutput is used, and hence outputData element is ignored");
				}
				ServiceInterface service = ComponentManager.getServiceOrNull(this.referredServiceForOutput);
				if (service == null) {
					ctx.addError("referredServiceForOutput set to " + this.referredServiceForOutput
							+ " but that service is not defined");
					count++;
				}
			}

			if (this.okToCache) {
				if (this.serviceCachesToInvalidate != null) {
					ctx.addError(
							"service can not cache as well as invalidate other service caches. A service can be cached only if it does not do any updates, and a service woudl invalidate other service caches only if does some update.");
					count++;
				}
			} else {
				if (this.cacheKeyNames != null || this.cacheValidityInMinutes != 0) {
					ctx.addError(
							"Caching attributes cacheKeyNames and cacheValidityInMinutes are relevant only if okToCache is set to true.");
					count++;
				}
			}

			if (this.serviceCachesToInvalidate != null) {
				for (String sn : this.serviceCachesToInvalidate) {
					ServiceInterface service = ComponentManager.getServiceOrNull(sn);
					if (service == null) {
						ctx.addError("service " + sn + " is marked for invalidation but that service is not defined");
						count++;
					} else if (service.okToCache() == false) {
						ctx.addError("service " + sn
								+ " is marked for invalidation but that service is not enabled for caching.");
						count++;
					}
				}
			}

			if (this.schemaName != null && DbDriver.isSchmeaDefined(this.schemaName) == false) {
				ctx.addError("schemaName is set to " + this.schemaName
						+ " but it is not defined as one of additional schema names in application.xml");
			}
			return count;
		} finally {
			ctx.endValidation();
		}
	}

	/**
	 * validate child actions
	 *
	 * @param ctx
	 * @return number of errors added
	 */
	private int validateChildren(ValidationContext ctx) {
		int count = 0;
		Set<String> addedSoFar = new HashSet<String>();
		int actionNbr = 0;
		boolean dbAccessErrorRaised = false;
		boolean delegated = this.dbAccessType == DbAccessType.SUB_SERVICE;
		for (Action action : this.actions) {
			actionNbr++;
			if (action.actionName != null) {
				if (addedSoFar.add(action.actionName) == false) {
					ctx.addError("Duplicate action name " + action.actionName + " at " + actionNbr);
					count++;
				}
			}
			count += action.validate(ctx, this);
			if (dbAccessErrorRaised || (delegated && (action instanceof SubService))) {
				continue;
			}

			if (this.dbAccessType.canWorkWithChildType(action.getDataAccessType()) == false) {
				ctx.addError(
						"dbAccessType of service is not compatible for the cbAccessType of its actions. Please review your db access design thoroughly based on your actions design.");
				count++;
				dbAccessErrorRaised = true;
			}
		}
		return count;
	}

	@Override
	public ComponentType getComponentType() {
		return MY_TYPE;
	}

	/**
	 * generate a service on the fly, if possible
	 *
	 * @param serviceName
	 *            that follows on-the-fly-service-name pattern
	 * @return service, or null if name is not a valid on-the-fly service name
	 */
	public static ServiceInterface generateService(String serviceName) {

		logger.info("Trying to generate service " + serviceName + " on-the-fly");

		int idx = serviceName.indexOf(PREFIX_DELIMITER);
		if (idx == -1) {
			return null;
		}

		String operation = serviceName.substring(0, idx);
		String recordName = serviceName.substring(idx + 1);
		/*
		 * once we have established that this name follows our on-the-fly naming
		 * convention, we are quite likely to to get the record.
		 */

		Record record = ComponentManager.getRecordOrNull(recordName);
		if (record == null) {

			logger.info(recordName + " is not defined as a record, and hence we are unable to generate a service named "
					+ serviceName);

			return null;
		}
		if (operation.equals(LIST)) {
			return getListService(serviceName, record);
		}

		if (operation.equals(FILTER)) {
			return getFilterService(serviceName, record);
		}
		if (operation.equals(GET)) {
			return getReadService(serviceName, record);
		}
		if (operation.equals(SAVE)) {
			return getSaveService(serviceName, record);
		}
		if (operation.equals(SUGGEST)) {
			return getSuggestionService(serviceName, record);
		}

		logger.info("We have no on-the-fly service generator for operation " + operation);

		return null;
	}

	/** definitions object */
	private static final String REF_ATTR = "$ref";

	private static final String SERVICE_NAME_ATTR = "x-serviceName";
	private static final String MODULE_NAME_ATTR = "x-moduleName";
	private static final String FORMAT_ATT = "format";
	private static final String PARAMETERS_ATTR = "parameters";
	private static final String RESPONSES_ATTR = "responses";
	private static final String SCHEMA_ATTR = "schema";
	private static final String TYPE_ATTR = "type";

	public static Service[] fromSwaggerPaths(JSONObject pathsObj, JSONObject defs) {
		Service[] empty = new Service[0];
		if (pathsObj == null) {
			return empty;
		}
		String[] paths = JSONObject.getNames(pathsObj);
		if (paths.length == 0) {
			return empty;
		}

		List<Service> services = new ArrayList<Service>();
		for (String path : paths) {
			parsePathSchema(pathsObj.getJSONObject(path), services, paths, defs);
		}

		if (services.size() == 0) {
			return empty;
		}
		return services.toArray(empty);
	}

	private static void parsePathSchema(JSONObject pathObject, List<Service> services, String[] names,
			JSONObject defs) {
		Service[] pathServices = fromSwagger(pathObject, names, defs);
		if (pathServices != null) {
			for (Service service : pathServices) {
				services.add(service);
			}
		}
	}

	private static Service[] fromSwagger(JSONObject pathObject, String[] names, JSONObject defs) {
		if (pathObject == null) {
			// Tracer.trace("schema for record " + recordName + " is null");
			return null;
		}
		String[] methods = JSONObject.getNames(pathObject);
		if (methods == null || methods.length == 0) {

			return null;
		}
		Service[] services = new Service[methods.length];
		int count = 0;
		for (String method : methods) {
			JSONObject methodObj = (JSONObject) pathObject.get(method);
			String moduleName = null;
			String serviceName = null;
			moduleName = methodObj.optString(MODULE_NAME_ATTR);
			serviceName = methodObj.optString(SERVICE_NAME_ATTR);
			if (serviceName == null || serviceName.isEmpty()) {
				serviceName = methodObj.optString("operationId");
			}
			Service service = new Service();
			service.moduleName = moduleName;
			service.setName(serviceName);
			JSONArray parameters = null;
			JSONObject responses = null;
			if (methodObj.has(PARAMETERS_ATTR)) {
				parameters = methodObj.getJSONArray(PARAMETERS_ATTR);
				service.inputData = getInputDataFromSwagger(parameters, defs);
			}
			if (methodObj.has(RESPONSES_ATTR)) {
				responses = methodObj.getJSONObject(RESPONSES_ATTR);
				service.outputData = getOuputDataFromSwagger(responses, defs);
			}

			if (method.equals("get")) {
				service.dbAccessType = DbAccessType.READ_ONLY;
				if (responses == null || responses.length() == 0 || !responses.has("200")) {
					return null;
				}
				JSONObject response = responses.getJSONObject("200");
				if (response.has(SCHEMA_ATTR)) {
					JSONObject schema = response.getJSONObject(SCHEMA_ATTR);
					String ref = schema.optString(REF_ATTR);
					String def = ref.substring(ref.lastIndexOf('/') + 1);
					if (def != null) {
						Record rec = ComponentManager.getRecord(def);
						RelatedRecord[] childRecs = getChildRecords(rec, false);
						Action action = new Read(rec, childRecs);
						Action[] actions = { action };
						service.actions = actions;
					}
				}
			}

			if (method.equals("post") || method.equals("put") || method.equals("patch")) {
				service.dbAccessType = DbAccessType.READ_WRITE;
				if (parameters == null || parameters.length() == 0) {
					return null;
				}
				int nbr = parameters.length();
				for (int i = 0; i < nbr; i++) {
					JSONObject params = parameters.getJSONObject(i);
					String def = null;
					if (params.has(SCHEMA_ATTR)) {
						JSONObject schema = params.getJSONObject(SCHEMA_ATTR);
						String ref = schema.optString(REF_ATTR);
						def = ref.substring(ref.lastIndexOf('/') + 1);
						if (def != null) {
							Record rec = ComponentManager.getRecord(def);
							RelatedRecord[] childRecs = getChildRecords(rec, false);
							Action action = new Save(rec, childRecs);
							Action[] actions = { action };
							service.actions = actions;
						}
					}
				}
			}
			services[count] = service;
			count++;
		}
		return services;
	}

	private static InputData getInputDataFromSwagger(JSONArray parameters, JSONObject defs) {
		InputData inData = new InputData();
		if (parameters == null || parameters.length() == 0) {
			return null;
		}
		int nbr = parameters.length();
		List<InputField> inFields = new ArrayList<InputField>();
		List<InputRecord> inputRecords = new ArrayList<InputRecord>();
		for (int i = 0; i < nbr; i++) {
			JSONObject params = parameters.getJSONObject(i);
			InputField field = new InputField();
			String def = null;
			if (params.has(SCHEMA_ATTR)) {
				JSONObject schema = params.getJSONObject(SCHEMA_ATTR);
				String ref = schema.optString(REF_ATTR);
				def = ref.substring(ref.lastIndexOf('/') + 1);
				if (def != null) {
					Record rec = Record.fromSwagger(defs.getJSONObject(def), def, null, JSONObject.getNames(defs));
					for (InputRecord inrec : getInputRecords(rec)) {
						inputRecords.add(inrec);
					}
				}
			} else {
				field.setName(params.getString("name"));
				setTypeForSwaggerType(field, params);
				inFields.add(field);
			}
		}
		inData.setInputFields(inFields.toArray(new InputField[inFields.size()]));
		inData.setRecords(inputRecords.toArray(new InputRecord[inputRecords.size()]));
		return inData;
	}

	private static OutputData getOuputDataFromSwagger(JSONObject responses, JSONObject defs) {
		OutputData outData = new OutputData();
		List<String> outFields = new ArrayList<String>();
		List<OutputRecord> outputRecords = new ArrayList<OutputRecord>();

		if (responses == null || responses.length() == 0 || !responses.has("200")) {
			return null;
		}
		JSONObject response = responses.getJSONObject("200");
		JSONArray parameters = null;
		if (response.has(PARAMETERS_ATTR)) {
			parameters = response.getJSONArray(PARAMETERS_ATTR);
			int nbr = parameters.length();
			for (int i = 0; i < nbr; i++) {
				JSONObject params = parameters.getJSONObject(i);
				String def = null;
				if (params.has(SCHEMA_ATTR)) {
					JSONObject schema = params.getJSONObject(SCHEMA_ATTR);
					String ref = schema.optString(REF_ATTR);
					def = ref.substring(ref.lastIndexOf('/') + 1);
					if (def != null) {
						Record rec = ComponentManager.getRecord(def);
						rec.getReady();
						for (OutputRecord outrec : getOutputRecords(rec)) {
							outputRecords.add(outrec);
						}
					}
				} else {
					outFields.add(params.get("name").toString());
				}
			}
		}
		if (response.has(SCHEMA_ATTR)) {
			JSONObject schema = response.getJSONObject(SCHEMA_ATTR);
			String ref = schema.optString(REF_ATTR);
			String def = ref.substring(ref.lastIndexOf('/') + 1);
			if (def != null) {
				Record rec = ComponentManager.getRecord(def);
				rec.getReady();
				for (OutputRecord outrec : getOutputRecords(rec)) {
					outputRecords.add(outrec);
				}
			}
		}
		outData.setOutputFields(outFields.toArray(new String[outFields.size()]));
		outData.setOutputRecords(outputRecords.toArray(new OutputRecord[outputRecords.size()]));
		return outData;
	}

	/**
	 * @param param
	 */
	public static void setTypeForSwaggerType(InputField field, JSONObject param) {
		String type = param.optString(TYPE_ATTR, null);
		String format = param.optString(FORMAT_ATT, null);
		/*
		 * default text
		 */
		field.setDataType(DataType.DEFAULT_TEXT);
		if (type == null) {
			return;
		}
		if (type.equals("string")) {
			if (format != null) {
				if (format.equals("date")) {
					field.setDataType(DataType.DEFAULT_DATE);
				} else if (format.equals("date-time")) {
					field.setDataType(DataType.DEFAULT_DATE_TIME);
				}
			}
			return;
		}
		if (type.equals("integer")) {
			field.setDataType(DataType.DEFAULT_NUMBER);
			return;
		}
		if (type.equals("number")) {
			field.setDataType(DataType.DEFAULT_DECIMAL);
			return;
		}
		if (type.equals("boolean")) {
			field.setDataType(DataType.DEFAULT_BOOLEAN);
			return;
		}
		/*
		 * use text as default
		 */

		return;
	}

	/**
	 * * @return
	 */
	public String[] getServicesToInvalidate() {
		return this.serviceCachesToInvalidate;
	}

	public int getCacheRefreshTime() {
		return this.cacheValidityInMinutes;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.simplity.service.ServiceInterface#getInputSpecification()
	 */
	@Override
	public InputData getInputSpecification() {
		return this.inputData;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.simplity.service.ServiceInterface#getOutputSpecification()
	 */
	@Override
	public OutputData getOutputSpecification() {
		return this.outputData;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.simplity.service.ServiceInterface#serve(org.simplity.service.
	 * ServiceContext)
	 */

	@Override
	public void serve(ServiceContext ctx) {
		ApplicationError err = this.executeService(ctx);
		if (err != null) {
			throw err;
		}
		if (this.okToCache()) {
			String key = createCachingKey(this.getQualifiedName(), this.cacheKeyNames, ctx);
			ctx.setCaching(key, this.cacheValidityInMinutes);
		} else if (this.serviceCachesToInvalidate != null) {
			ctx.setInvalidations(this.getInvalidations(ctx));
		}
	}

	private String[] getInvalidations(ServiceContext ctx) {
		String[] result = new String[this.serviceCachesToInvalidate.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = createCachingKey(this.serviceCachesToInvalidate[i], this.invalidationKeys[i], ctx);
		}
		return result;
	}

	/**
	 * separator character between key field values to form a single string of
	 * key
	 */
	public static final char CACHE_KEY_SEP = '\0';

	/**
	 * @param ctx
	 * @return
	 */
	private static String createCachingKey(String serviceName, String[] keyNames, ServiceContext ctx) {
		if (keyNames == null) {
			return createCachingKey(serviceName, null);
		}
		String[] vals = new String[keyNames.length];
		/*
		 * first field could be userId
		 */
		int startAt = 0;
		if (keyNames[0].equals(ServiceProtocol.USER_ID)) {
			startAt = 1;
			vals[0]= ctx.getUserId().toString();
		}
		for (int i = startAt; i < keyNames.length; i++) {
			Value val = ctx.getValue(keyNames[i]);
			if (val != null) {
				vals[i] = val.toString();
			}
		}

		return createCachingKey(serviceName, vals);
	}

	/**
	 * form a key to be used for caching based on service name and values of
	 * keys. This method to be used for caching and retrieving
	 *
	 * @param serviceName
	 * @param keyValues
	 * @return key to be used for caching
	 */
	public static String createCachingKey(String serviceName, String[] keyValues) {
		if (keyValues == null) {
			return serviceName;
		}
		StringBuilder result = new StringBuilder(serviceName);
		for (String val : keyValues) {
			result.append(CACHE_KEY_SEP);
			if (val != null) {
				result.append(val);
			}
		}

		return result.toString();
	}

	@Override
	public String[] getCacheKeyNames() {
		return this.cacheKeyNames;
	}

	/**
	 * generates key for the service to be cached
	 *
	 * @param serviceName
	 * @param inputData
	 * @return
	 */
	public String generateKeyToCache(String serviceName, ServiceData inputData) {
		int hashKey = 0;
		String fields[] = null;
		if (this.cacheKeyNames != null) {
			hashKey = serviceName.hashCode();
			if (this.cacheKeyNames == null) {
				int i = 0;
				InputField[] inputFields = null;
				if (this.getInputSpecification() != null) {
					inputFields = this.inputData.getInputFields();
				}
				if (inputFields != null) {
					fields = new String[inputFields.length];
					for (InputField field : inputFields) {
						fields[i] = field.getName();
						i++;
					}
				}
			} else {
				fields = this.cacheKeyNames;
			}
			if (fields != null && fields.length > 0) {
				if (fields[0].equals(ServiceProtocol.USER_ID)) {
					hashKey += inputData.getUserId().hashCode();
					int n = fields.length;
					if (n > 0) {
						n--;
						String[] newFields = new String[n];
						for (int i = 0; i < newFields.length; i++) {
							newFields[i] = fields[i + 1];
						}
						fields = newFields;
					}
				}
				for (String field : fields) {
					hashKey += inputData.get(field).hashCode();
				}
			}
		}

		return String.valueOf(hashKey);
	}

}
