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

package org.simplity.tp;

/**
 * @author simplity.org
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Properties;

import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import org.simplity.kernel.value.Value;
import org.simplity.service.ServiceContext;

public class SendMail extends Action {

	String fromId;
	String toIds;
	String ccIds;
	String bccIds;
	String subject;
	String attachmentSheetName;

	Content content;


	private Properties props = new Properties();

	public SendMail() {
	}

	@Override
	protected Value doAct(ServiceContext ctx) {

		try {
			props.load(this.getClass().getClassLoader().getResourceAsStream("config.properties"));
		} catch (IOException e) {
			e.printStackTrace();
		}

		Mail mail = new Mail();
		mail.fromId = fromId;
		mail.toIds = toIds;
		mail.ccIds = ccIds;
		mail.bccIds = bccIds;
		mail.subject = subject;

		try {
			ctx.setObject("mail", new ByteArrayInputStream(SendMail.serialize(mail)));
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		Session session = Session.getInstance(props, null);
		sendEmail(session, mail);

		return Value.newBooleanValue(true);

	}

	private void sendEmail(Session session, Mail mail) {
		try {
			MimeMessage msg = new MimeMessage(session);
			msg.addHeader("Content-type", "text/HTML; charset=UTF-8");
			msg.addHeader("format", "flowed");
			msg.addHeader("Content-Transfer-Encoding", "8bit");
			msg.setFrom(new InternetAddress(mail.fromId, "NoReply-JD"));
			msg.setReplyTo(InternetAddress.parse(mail.fromId, false));
			msg.setSubject(mail.subject, "UTF-8");
			msg.setSentDate(new Date());
			msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(mail.toIds, false));

			msg.writeTo(System.out);
			Transport.send(msg);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (MessagingException e) {
			e.printStackTrace();
		}
	}

	private static byte[] serialize(Object obj) throws IOException {

		ByteArrayOutputStream b = new ByteArrayOutputStream();

		ObjectOutputStream o = new ObjectOutputStream(b);
		o.writeObject(obj);

		return b.toByteArray();
	}
}

class Mail implements Serializable {
	private static final long serialVersionUID = -4314888435710523295L;

	public String fromId;
	public String toIds;
	public String ccIds;
	public String bccIds;
	public String subject;
	public Content content;
	public MailAttachement attachment;
}

class MailAttachement implements Serializable {

	private static final long serialVersionUID = 8189730674999834850L;

	public String filekey;
	public String filename;

	public boolean isEmpty() {
		if (filekey == null || filekey.isEmpty())
			return true;
		return false;
	}
}

enum ContentType{
	TEXT,
	TEMPLATE
}