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

package org.simplity.service;

import java.util.HashMap;
import java.util.Map;

import org.simplity.kernel.Tracer;
import org.simplity.kernel.value.Value;

/**
 * @author simplity.org
 *
 */
public class JavaAgent {
	/**
	 * a client agent that can be used by java classes within the same VM where
	 * the app is hosted. Useful for any java code to call a service. For
	 * example a class that wants to test a service. This is similar to
	 * HttpAgent that is used by HTTP clients
	 *
	 * @param loginId
	 * @param pwd
	 * @return agent if credentials are fine, null otherwise.
	 */
	public static JavaAgent getAgent(String loginId, String pwd) {

		JavaAgent agent = new JavaAgent();
		if (agent.login(loginId, pwd)) {
			return agent;
		}
		return null;
	}

	/**
	 * userId for whom this agent is created
	 */
	private Value userId;

	/**
	 * session parameters stored. Similar to HTTP session.
	 */
	private Map<String, Object> sessionData;

	/**
	 * execute the service with
	 *
	 * @param serviceName
	 * @param payLoad
	 *            request parameters for the service
	 * @return response from the service
	 */
	public String serve(String serviceName, String payLoad) {
		ServiceData inData = new ServiceData(this.userId, serviceName);
		this.setSessionData(inData);
		String pl = payLoad;
		if (payLoad == null || payLoad.isEmpty()) {
			pl = "{}";
		}
		inData.setPayLoad(pl);
		ServiceData outData = ServiceAgent.getAgent().executeService(inData);
		Tracer.trace(outData.getTrace());
		return outData.getResponseJson();
	}

	private boolean login(String loginId, String pwd) {
		/*
		 * ask serviceAgent to login.
		 */
		ServiceData inData = new ServiceData();
		inData.put(ServiceProtocol.USER_ID, Value.newTextValue(loginId));
		if (pwd != null) {
			inData.put(ServiceProtocol.USER_TOKEN, Value.newTextValue(pwd));
		}
		inData.setPayLoad("{}");
		ServiceData outData = ServiceAgent.getAgent().login(inData);
		if (outData == null) {
			return false;
		}
		this.userId = outData.getUserId();
		if (this.userId == null) {
			/*
			 * possible that loginService is a custom one. Let us try to fish in
			 * the Map
			 */
			Object uid = outData.get(ServiceProtocol.USER_ID);
			if (uid == null) {
				Tracer.trace("Server came back with no userId and hence HttpAgent assumes that the login did not succeed");
				return false;
			}

			if (uid instanceof Value) {
				this.userId = (Value) uid;
			} else {
				this.userId = Value.parseObject(uid);
			}
		}

		/*
		 * create and save new session data
		 */
		this.sessionData = new HashMap<String, Object>();
		this.saveSessionData(outData);
		return true;
	}

	/**
	 * save session data from output data
	 *
	 * @param outData
	 */
	private void saveSessionData(ServiceData outData) {
		for (String key : outData.getFieldNames()) {
			this.sessionData.put(key, outData.get(key));
		}
	}

	/**
	 * extract session data into inData
	 *
	 * @param inData
	 */
	private void setSessionData(ServiceData inData) {
		for (Map.Entry<String, Object> entry : this.sessionData.entrySet()) {
			inData.put(entry.getKey(), entry.getValue());
		}
	}
}
