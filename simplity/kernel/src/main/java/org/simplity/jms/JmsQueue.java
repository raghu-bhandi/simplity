/*
 * Copyright (c) 2017 simplity.org
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

package org.simplity.jms;

import java.io.Serializable;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.UUID;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.simplity.json.JSONObject;
import org.simplity.json.JSONWriter;
import org.simplity.kernel.ApplicationError;
import org.simplity.kernel.Tracer;
import org.simplity.kernel.comp.ComponentManager;
import org.simplity.kernel.comp.ValidationContext;
import org.simplity.kernel.data.DataSheet;
import org.simplity.kernel.data.MultiRowsSheet;
import org.simplity.kernel.dm.Record;
import org.simplity.kernel.util.JsonUtil;
import org.simplity.kernel.value.Value;
import org.simplity.kernel.value.ValueType;
import org.simplity.service.DataExtractor;
import org.simplity.service.DataFormatter;
import org.simplity.service.ServiceContext;

/**
 * A data structure that capture all details of a JmsQueue
 *
 * @author simplity.org
 *
 */
public class JmsQueue {

	private static final char EQUAL = '=';
	private static final char COMMA = ',';
	private static final String COMA = ",";
	/**
	 * name of the queue (destination) used for requesting a service
	 */
	String queueName;
	/**
	 * null if message body is not used, but header parameters are used to
	 * transport data.
	 * How the body of the message is used to transport data.
	 */
	MessageBodyType messageBodyType;
	/**
	 * field that is associated with body. Entire body is
	 * assigned-to/retrieved-from this field
	 */
	String bodyFieldName;
	/**
	 * comma separated list of fields that supply/receive data.
	 */
	String fieldNames[];
	/**
	 * data structure on which this message data is based on
	 */
	String recordName;
	/**
	 * just extract all fields with no validation, if this queue is being
	 * consumed
	 */
	boolean extractAll;
	/**
	 * java class that implements org.simplity.service.DataFormatter interface.
	 * message body text is formatted using this class.
	 */
	String messageFormatter;
	/**
	 * java class that implements org.simplity.service.DataExtractor interface
	 * to
	 * extract data from message body text
	 */
	String messageExtractor;

	/**
	 * sheet name with two columns, first one name, second one value, both text.
	 * bodyMessageType must e set to COMMA_SEPARATED_PAIRS. Consumer extracts
	 * into this sheet, while producer formats the text using data in this
	 * sheet
	 */
	String nameValueSheetName;
	/**
	 * subset of messages that we are interested. As per JMS selector syntax.
	 */
	String messageSelector;
	/**
	 * message type to the message header. Use this ONLY if the provider insists
	 * on this, or your application uses this.
	 */
	String messageType;

	/**
	 * object instance for re-use
	 */
	private DataFormatter dataFormatter;
	/**
	 * object instance for re-use
	 */
	private DataExtractor dataExtractor;

	/**
	 * consume a request queue, and optionally put a message on to the response
	 * queue. Keep doing this for messages in the request queue, till the queue
	 * is closed, or the processor signals a shut-down
	 *
	 * @param ctx
	 *            service context where all this is happening
	 *
	 * @param processor
	 *            object instance that is interested in processing the message
	 * @param session
	 * @param responseQ
	 *            optional response queue to be used to respond back to the
	 *            incoming message
	 */
	public void consume(ServiceContext ctx, MessageClient processor,
			Session session, JmsQueue responseQ) {
		MessageConsumer consumer = null;
		MessageProducer producer = null;

		try {
			/*
			 * We may not use producer at all, but an empty producer does not
			 * hurt as much as creating it repeatedly..
			 */
			producer = session.createProducer(null);

			Queue request = session.createQueue(this.queueName);
			consumer = session.createConsumer(request, this.messageSelector);
			/*
			 * default response q is decided by queueName, but may be
			 * over-ridden my incoming message.Let us keep this default one
			 * handy
			 */
			Queue response = null;
			if (responseQ != null) {
				if (responseQ.queueName != null) {
					response = session.createQueue(responseQ.queueName);
				}
			}
			/*
			 * seemingly infinite loop starts...
			 */
			while (processor.toContinue()) {
				Message msg = consumer.receive();
				if (msg == null) {
					Tracer.trace("Queue " + this.queueName
							+ " has shutdown. Hence this consumer is also shutting down");
					/*
					 * queue is shut down
					 */
					break;
				}
				boolean allOk = false;
				/*
				 * let exception in one message not affect the over-all process
				 */
				try {
					/*
					 * data content of message is extracted into ctx
					 */
					this.extractMessage(msg, ctx);
					/*
					 * is the requester asking us to respond on a specific
					 * queue?
					 */
					Destination reply = msg.getJMSReplyTo();
					/*
					 * and the all important correlation id for the requester to
					 * select the message back
					 */
					String corId = msg.getJMSCorrelationID();
					if (reply == null) {
						reply = response;
					}

					allOk = processor.process(ctx);
					if (reply != null) {
						Message respMsg = null;
						if (responseQ == null) {
							Tracer.trace(
									"No response is specified for this consumer, but producer is asking for a reply. Sending a blank message");
							respMsg = session.createMessage();
						} else {
							/*
							 * prepare a reply based on specification
							 */
							respMsg = responseQ.createMessage(session, ctx);
						}
						if (corId != null) {
							respMsg.setJMSCorrelationID(corId);
						}
						producer.send(reply, respMsg);
					}
				} catch (Exception e) {
					Tracer.trace("Message processor threw an excpetion. "
							+ e.getMessage());
				}
				if (allOk) {
					session.commit();
				} else {
					Tracer.trace(
							"Rolling back message as the processor crashed/returned a flase value");
					session.rollback();
				}
			}
		} catch (Exception e) {
			throw new ApplicationError(e,
					"Error while consuming and procesing JMS queue "
							+ this.queueName);

		} finally {
			if (consumer != null) {
				try {
					consumer.close();
				} catch (Exception ignore) {
					//
				}
			}
			if (producer != null) {
				try {
					producer.close();
				} catch (Exception ignore) {
					//
				}
			}
		}
	}

	/**
	 * produce message on this queue
	 *
	 * @param ctx
	 *            service context where this is all happening
	 *
	 * @param session
	 * @param responseQ
	 *            in case we are to get a response for this message-send
	 *            operation
	 * @return true if a message indeed was put on the queue. False otherwise
	 */
	public boolean produce(ServiceContext ctx, Session session,
			JmsQueue responseQ) {
		MessageProducer producer = null;
		MessageConsumer consumer = null;
		Queue response = null;
		String corId = null;

		try {
			Queue request = session.createQueue(this.queueName);
			producer = session.createProducer(request);
			/*
			 * create a message with data from ctx
			 */
			Message msg = this.createMessage(session, ctx);

			/*
			 * should we ask for a return message?
			 */
			if (responseQ != null) {
				if (responseQ.queueName == null) {
					response = session.createTemporaryQueue();
					consumer = session.createConsumer(response);
				} else {
					response = session.createQueue(responseQ.getQueueName());
					corId = UUID.randomUUID().toString();
					consumer = session.createConsumer(response,
							"JMSCorrelationID='" + corId + '\'');
					msg.setJMSCorrelationID(corId);
				}
				msg.setJMSReplyTo(response);
			}
			producer.send(msg);
			/*
			 * checking for one of them is good enough, but compiler would crib.
			 * We would rather live with whatever is the over-head of checking
			 * for another null than loosing the null-check facility for this
			 * method
			 */
			if (consumer != null && responseQ != null) {
				Message message = consumer.receive();
				if (message == null) {
					/*
					 * some issue in the queue
					 */
					Tracer.trace(
							"Response message is null. Probably som eissue with the queue provider");
					return false;
				}
				responseQ.extractMessage(message, ctx);
			}
			return true;
		} catch (Exception e) {
			Tracer.trace("Error while putting mesage on tp a queue. "
					+ e.getMessage());
			return false;
		} finally {
			if (consumer != null) {
				try {
					consumer.close();
				} catch (Exception ignore) {
					//
				}
			}
			if (producer != null) {
				try {
					producer.close();
				} catch (Exception ignore) {
					//
				}
			}
		}
	}

	/**
	 * @param session
	 * @param ctx
	 * @return
	 * @throws JMSException
	 */
	private Message createMessage(Session session, ServiceContext ctx)
			throws JMSException {
		if (this.dataFormatter != null) {
			String text = this.dataFormatter.format(ctx);
			return session.createTextMessage(text);
		}

		if (this.messageBodyType == null) {
			/*
			 * properties are used for transporting data
			 */
			Message message = session.createMessage();
			if (this.fieldNames != null) {
				this.setHeaderFields(message, ctx, this.fieldNames);
			} else if (this.recordName != null) {
				Record record = ComponentManager.getRecord(this.recordName);
				this.setHeaderFields(message, ctx, record.getFieldNames());
			} else {
				Tracer.trace("No fields specified to be added to the message.");
			}
			return message;
		}

		if (this.messageBodyType == MessageBodyType.MAP) {
			MapMessage message = session.createMapMessage();
			if (this.fieldNames != null) {
				this.setMapFields(message, ctx, this.fieldNames);
			} else if (this.recordName != null) {
				Record record = ComponentManager.getRecord(this.recordName);
				this.setMapFields(message, ctx, record.getFieldNames());
			} else {
				Tracer.trace("No fields specified to be added Map.");
			}
			return message;
		}

		if (this.messageBodyType == MessageBodyType.OBJECT) {
			if (this.bodyFieldName == null) {
				throw new ApplicationError(
						"bodyFieldName is not specified for messaage body when messageBodyType is set to object");
			}
			Object object = ctx.getObject(this.bodyFieldName);
			if (object == null) {
				Tracer.trace("Service context has no object named "
						+ this.bodyFieldName
						+ ". No object assigned to message.");
				return session.createObjectMessage();
			}
			if (object instanceof Serializable) {
				return session.createObjectMessage((Serializable) object);
			}
			throw new ApplicationError("Service context has an instance of "
					+ object.getClass().getName()
					+ " as object for message. This class must be serializable.");
		}

		/*
		 * so, it is a TextMessage. Our task is to create the text to be set to
		 * the message body
		 */
		TextMessage message = session.createTextMessage();
		String text = null;

		if (this.bodyFieldName != null) {
			/*
			 * simplest of our task. text is readily available in this field.
			 */
			text = ctx.getTextValue(this.bodyFieldName);
			if (text == null) {
				Tracer.trace("No value found for body text with field name "
						+ this.bodyFieldName + ". Data not set.");
			} else {
				message.setText(text);
			}
			return message;
		}

		/*
		 * format the text based on the data format specification
		 */
		switch (this.messageBodyType) {
		case COMMA_SEPARATED:
			text = this.formatComma(ctx);
			break;
		case COMMA_SEPARATED_PAIRS:
			text = this.formatCommaPairs(ctx);
			break;
		case FIXED_WIDTH:
			text = this.formatFixed(ctx);
			break;
		case FORM_DATA:
			text = this.formatFormData(ctx);
			break;
		case JSON:
			text = this.formatJson(ctx);
			break;
		case XML_ATTRIBUTES:
			text = this.formatXmlAtts(ctx);
			break;
		case XML_ELEMENTS:
			text = this.formatXmlElements(ctx);
			break;
		case YAML:
			text = this.formatYaml(ctx);
			break;
		default:
			throw new ApplicationError("Sorry. Body Messaage Type of "
					+ this.messageBodyType
					+ " is not yet implemented to create data for a JMS message. Use alternative method as of now");
		}
		message.setText(text);
		return message;
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatYaml(ServiceContext ctx) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to create data for a JMS message. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatXmlElements(ServiceContext ctx) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to create data for a JMS message. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatXmlAtts(ServiceContext ctx) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to create data for a JMS message. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatJson(ServiceContext ctx) {
		String[] names = this.fieldNames;
		if (names == null) {
			names = ComponentManager.getRecord(this.recordName).getFieldNames();
		}
		JSONWriter writer = new JSONWriter();
		JsonUtil.addAttributes(writer, names, ctx);
		return writer.toString();
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatFormData(ServiceContext ctx) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to create data for a JMS message. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatFixed(ServiceContext ctx) {
		Record record = ComponentManager.getRecord(this.recordName);
		String[] names = record.getFieldNames();
		Value[] values = ctx.getValues(names);
		return record.formatFixedLine(values);
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatCommaPairs(ServiceContext ctx) {
		if(this.nameValueSheetName != null){
			return this.formatFromSheet(ctx);
		}
		String[][] data = this.getNamesAndValues(ctx);
		String[] names = data[0];
		String[] values = data[1];
		int i = 0;
		StringBuilder sbf = new StringBuilder();
		for (String name : names) {
			sbf.append(name).append(EQUAL).append(values[i]).append(COMMA);
			i++;
		}
		sbf.setLength(sbf.length() - 1);
		return sbf.toString();
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatFromSheet(ServiceContext ctx) {
		DataSheet sheet = ctx.getDataSheet(this.nameValueSheetName);
		if(sheet == null){
			Tracer.trace("No data sheet named " + this.nameValueSheetName + ". No data added to message.");
			return null;
		}

		int nbr = sheet.length();
		if(nbr == 0){
			Tracer.trace("Data sheet named " + this.nameValueSheetName + " is found, but it is empty. No data added to message.");
			return null;
		}

		if(sheet.width() != 2){
			Tracer.trace("Data sheet named " + this.nameValueSheetName + " is to have just two columns, first one for name and second one for value. " + sheet.width() + " columns found.");
			return null;
		}
		StringBuilder sbf = new StringBuilder();
		for(int i = 0; i < nbr; i++){
			Value[] row = sheet.getRow(i);
			sbf.append(row[0].toString()).append(EQUAL).append(row[1]).append(COMMA);
		}
		sbf.setLength(sbf.length() - 1);
		return sbf.toString();
	}

	/**
	 * @param ctx
	 * @return
	 */
	private String formatComma(ServiceContext ctx) {
		String[][] data = this.getNamesAndValues(ctx);
		String[] values = data[1];
		StringBuilder sbf = new StringBuilder();
		for (String value : values) {
			sbf.append(value).append(COMMA);
		}
		sbf.setLength(sbf.length() - 1);
		return sbf.toString();
	}

	private String[] getNames() {
		if (this.fieldNames != null) {
			return this.fieldNames;
		}
		return ComponentManager.getRecord(this.recordName).getFieldNames();
	}

	private String[][] getNamesAndValues(ServiceContext ctx) {
		String[] names = this.getNames();
		String[] values = new String[names.length];
		int i = 0;
		for (String name : names) {
			String val = ctx.getTextValue(name);
			if (val == null) {
				val = "";
			}
			values[i] = val;
			i++;
		}
		String[][] result = { names, values };
		return result;
	}

	/**
	 *
	 * @param message
	 * @param ctx
	 * @throws JMSException
	 */
	public void extractMessage(Message message, ServiceContext ctx)
			throws JMSException {
		if (this.dataExtractor != null) {
			Tracer.trace("Using a custom class to extract data "
					+ this.messageExtractor);
			if (message instanceof TextMessage == false) {
				throw new ApplicationError("Expecting a TextMessage on queue "
						+ this.bodyFieldName + " but we got a "
						+ message.getClass().getSimpleName());
			}
			String text = ((TextMessage) message).getText();
			this.dataExtractor.extract(text, ctx);
			return;
		}

		if (this.messageBodyType == null) {
			/*
			 * properties are used for transporting data
			 */
			if (this.extractAll) {
				this.extractAllFromHeader(message, ctx);
			} else if (this.fieldNames != null && this.fieldNames.length > 0) {
				this.extractHeaderFields(message, ctx, this.fieldNames);
			} else if (this.recordName != null) {
				Record record = ComponentManager.getRecord(this.recordName);
				this.extractHeaderFields(message, ctx, record.getFieldNames());
			} else {

				Tracer.trace(
						"No fields specified to be extracted from the message. Nothing extracted. ");
			}
			return;
		}
		/*
		 * we use three types of message body. TEXT, MAP and OBJECT
		 */
		if (this.messageBodyType == MessageBodyType.OBJECT) {
			if (message instanceof ObjectMessage == false) {
				Tracer.trace("We expected a ObjectMessage but got "
						+ message.getClass().getSimpleName()
						+ ". No object extracted.");
				return;
			}
			Object object = ((ObjectMessage) message).getObject();
			if (object == null) {
				Tracer.trace("Messaage object is null. No object extracted.");
			} else if (this.bodyFieldName == null) {
				Tracer.trace(
						"bodyFieldName not set, and hence the object of instance "
								+ object.getClass().getName() + "  " + object
								+ " not added to context.");
			} else {
				ctx.setObject(this.bodyFieldName, object);
			}
			return;
		}

		if (this.messageBodyType == MessageBodyType.MAP) {
			if (message instanceof MapMessage == false) {
				Tracer.trace("We expected a MapMessage but got "
						+ message.getClass().getSimpleName()
						+ ". No data extracted.");
				return;
			}
			MapMessage msg = (MapMessage) message;
			if (this.extractAll) {
				this.extractAllFromMap(ctx, msg);
			} else if (this.fieldNames != null) {
				this.extractMapFields(ctx, msg, this.fieldNames);
			} else if (this.recordName != null) {
				Record record = ComponentManager.getRecord(this.recordName);
				this.extractMapFields(ctx, msg, record.getFieldNames());
			} else {
				Tracer.trace(
						"No directive to extract any fields from this MapMessage.");
			}
			return;

		}

		if (message instanceof TextMessage == false) {
			Tracer.trace("We expected a TextMessage but got "
					+ message.getClass().getSimpleName()
					+ ". No data extracted.");
			return;
		}

		String text = ((TextMessage) message).getText();
		if (text == null) {
			Tracer.trace("Messaage text is null. No data extracted.");
		} else if (this.bodyFieldName != null) {
			ctx.setTextValue(this.bodyFieldName, text);
		} else {
			this.extractFromText(ctx, text);
		}
	}

	/**
	 * complex case wher
	 *
	 * @param ctx
	 * @param text
	 */
	private void extractFromText(ServiceContext ctx, String text) {
		switch (this.messageBodyType) {
		case COMMA_SEPARATED:
			this.extractComma(ctx, text);
			return;
		case COMMA_SEPARATED_PAIRS:
			this.extractCommaPairs(ctx, text);
			return;
		case FIXED_WIDTH:
			this.extractFixed(ctx, text);
			return;
		case FORM_DATA:
			this.extractFormData(ctx, text);
			return;
		case JSON:
			this.extractJson(ctx, text);
			return;
		case XML_ATTRIBUTES:
			this.extractXml(ctx, text);
			return;
		case XML_ELEMENTS:
			this.extractXml(ctx, text);
			return;
		case YAML:
			this.extractYaml(ctx, text);
			return;
		default:
			break;
		}
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to exract data from a message.. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractYaml(ServiceContext ctx, String text) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to exract data from a message.. Use alternative method as of now");

	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractXml(ServiceContext ctx, String text) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to exract data from a message.. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractJson(ServiceContext ctx, String text) {
		JSONObject json = new JSONObject(text);
		if (this.extractAll) {
			JsonUtil.extractAll(json, ctx);
			return;
		}
		String[] names = this.getNames();
		JsonUtil.extractFields(json, names, ctx);
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractFormData(ServiceContext ctx, String text) {
		throw new ApplicationError("Sorry. Body Messaage Type of "
				+ this.messageBodyType
				+ " is not yet implemented to exract data from a message.. Use alternative method as of now");
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractFixed(ServiceContext ctx, String text) {
		Record record = ComponentManager.getRecord(this.recordName);
		Value[] values = record.parseRow(text, null);
		this.fillCtx(ctx, record.getFieldNames(), values);
	}

	/**
	 *
	 * @param ctx
	 * @param names
	 * @param values
	 */
	private void fillCtx(ServiceContext ctx, String[] names, Value[] values) {
		int i = 0;
		for (Value value : values) {
			String name = names[i];
			if (Value.isNull(value)) {
				Tracer.trace(
						"Field " + name + " is null. Not added to context.");
			} else {
				ctx.setValue(name, value);
			}
			i++;
		}
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractCommaPairs(ServiceContext ctx, String text) {
		if(this.nameValueSheetName != null){
			this.extractToSheet(ctx, text);
		}
		String[] parts = text.split(COMA);
		for (String part : parts) {
			String[] pair = part.split("=");
			if (pair.length == 2) {
				ctx.setValue(pair[0].trim(), Value.parseValue(pair[1].trim()));
			} else {
				Tracer.trace("Improper value-pair format " + part
						+ ". Part skipped");
			}
		}
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractToSheet(ServiceContext ctx, String text) {
		String[] parts = text.split(COMA);
		String[] columnNames = {"name", "value"};
		ValueType[] columnValueTypes = {ValueType.TEXT, ValueType.TEXT};
		DataSheet sheet = new MultiRowsSheet(columnNames, columnValueTypes);
		for (String part : parts) {
			String[] pair = part.split("=");
			if (pair.length == 2) {
				Value name = Value.newTextValue(pair[0].trim());
				Value val = Value.newTextValue(pair[1].trim());
				Value[] row = {name, val};
				sheet.addRow(row);
			} else {
				Tracer.trace("Improper value-pair format " + part
						+ ". Part skipped");
			}
		}
	}

	/**
	 * @param ctx
	 * @param text
	 */
	private void extractComma(ServiceContext ctx, String text) {
		String[] names = this.getNames();
		String[] parts = text.split(COMA);
		if (names.length != parts.length) {
			Tracer.trace("We expected " + names.length
					+ " fields in the message but got " + parts.length
					+ " values. No data extracted.");
			return;
		}

		int i = 0;
		for (String part : parts) {
			ctx.setValue(names[i], Value.parseValue(part.trim()));
			i++;
		}
	}

	/**
	 * @param ctx
	 * @param message
	 * @param names
	 * @throws JMSException
	 */
	private void extractMapFields(ServiceContext ctx, MapMessage message,
			String[] names) throws JMSException {
		for (String nam : names) {
			Object val = message.getObject(nam);
			if (val != null) {
				ctx.setValue(nam, Value.parseObject(val));
			}
		}
	}

	/**
	 * @param ctx
	 * @param message
	 * @throws JMSException
	 */
	private void extractAllFromMap(ServiceContext ctx, MapMessage message)
			throws JMSException {
		@SuppressWarnings("unchecked")
		Enumeration<String> names = message.getMapNames();
		while (true) {
			try {
				String nam = names.nextElement();
				Object val = message.getObject(nam);
				if (val != null) {
					ctx.setValue(nam, Value.parseObject(val));
				}
			} catch (NoSuchElementException e) {
				/*
				 * unfortunately we have to live with this old-styled
				 * exception for a normal event!!!
				 */
				return;
			}
		}
	}

	/**
	 * @param message
	 * @param ctx
	 * @throws JMSException
	 */
	private void extractAllFromHeader(Message message, ServiceContext ctx)
			throws JMSException {
		@SuppressWarnings("unchecked")
		Enumeration<String> names = message.getPropertyNames();
		while (true) {
			try {
				String nam = names.nextElement();
				Object val = message.getObjectProperty(nam);
				if (val != null) {
					ctx.setValue(nam, Value.parseObject(val));
				}
			} catch (NoSuchElementException e) {
				/*
				 * unfortunately we have to live with this old-styled
				 * exception for a normal event!!!
				 */
				return;
			}
		}
	}

	/**
	 *
	 * @param message
	 * @param ctx
	 * @param names
	 * @throws JMSException
	 */
	private void extractHeaderFields(Message message, ServiceContext ctx,
			String[] names) throws JMSException {
		for (String nam : names) {
			Object val = message.getObjectProperty(nam);
			if (val != null) {
				ctx.setValue(nam, Value.parseObject(val));
			}
		}

	}

	/**
	 *
	 * @param message
	 * @param ctx
	 * @param names
	 * @throws JMSException
	 */
	private void setHeaderFields(Message message, ServiceContext ctx,
			String[] names) throws JMSException {
		for (String nam : names) {
			Value val = ctx.getValue(nam);
			if (val == null) {
				Tracer.trace("No value for " + nam
						+ ". Value not set to message header.");
			} else {
				message.setObjectProperty(nam, val.toObject());
			}
		}

	}

	/**
	 *
	 * @param message
	 * @param ctx
	 * @param names
	 * @throws JMSException
	 */
	private void setMapFields(MapMessage message, ServiceContext ctx,
			String[] names) throws JMSException {
		for (String nam : names) {
			Value val = ctx.getValue(nam);
			if (val == null) {
				Tracer.trace("No value for " + nam + ". Value not set to Map.");
			} else {
				message.setObject(nam, val.toObject());
			}
		}

	}

	/**
	 * open shop and be ready for a repeated use
	 */
	public void getReady() {
		if (this.messageExtractor != null) {
			try {
				this.dataExtractor = (DataExtractor) Class
						.forName(this.messageExtractor).newInstance();
			} catch (Exception e) {
				throw new ApplicationError(e,
						"Error while creating an instance of DataExtractor for "
								+ this.messageExtractor);
			}
		}

		if (this.messageFormatter != null) {
			try {
				this.dataFormatter = (DataFormatter) Class
						.forName(this.messageFormatter).newInstance();
			} catch (Exception e) {
				throw new ApplicationError(e,
						"Error while creating an instance of DataFormatter for "
								+ this.messageFormatter);
			}
		}
	}

	/**
	 *
	 * @return name of this queue
	 */
	public String getQueueName() {
		return this.queueName;
	}

	/**
	 * validate attributes
	 *
	 * @param vtx
	 *            validation context
	 * @return number of errors detected
	 */
	public int validate(ValidationContext vtx) {
		// TODO : add all validation rules here
		return 0;
	}
}
