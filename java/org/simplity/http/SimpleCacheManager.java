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

package org.simplity.http;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.simplity.json.JSONObject;
import org.simplity.kernel.Tracer;
import org.simplity.service.ServiceData;
import org.simplity.service.ServiceProtocol;

/**
 * this is a singleton by design. An instance is cached by HttpAgent and reused.
 *
 * @author simplity.org
 *
 */
public class SimpleCacheManager implements HttpCacheManager {
	/**
	 * user id specific responses are saved in session in this name
	 */
	private static final String NAME_IN_SESSION = "_CACHE";

	/**
	 * responses that are independent of userId. Cached
	 */
	private final Map<String, CachedService> allCache = new HashMap<String, CachedService>();

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.simplity.http.HttpCacheManager#respond(org.simplity.service.ServiceData
	 * , javax.servlet.http.HttpSession)
	 */
	@Override
	public ServiceData respond(ServiceData inData, HttpSession session) {
		String serviceName = inData.getServiceName();
		CachedService cs = this.allCache.get(serviceName);
		String payLoad = null;
		if (cs == null) {
			@SuppressWarnings("rawtypes")
			Map map = (Map) session.getAttribute(NAME_IN_SESSION);
			if (map != null) {
				cs = (CachedService) map.get(serviceName);
			}
		}
		if (cs != null) {
			payLoad = cs.getResponse(inData);
		}
		if (payLoad == null) {
			Tracer.trace("Service not available in cached responses.");
			return null;
		}
		Tracer.trace("Responding from cache");
		ServiceData outData = new ServiceData(inData.getUserId(), serviceName);
		outData.setPayLoad(payLoad);
		return outData;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.simplity.http.HttpCacheManager#cache(org.simplity.service.ServiceData
	 * , org.simplity.service.ServiceData, javax.servlet.http.HttpSession)
	 */
	@Override
	public void cache(ServiceData inData, ServiceData outData,
			HttpSession session) {
		String text = outData.getCacheForInput();
		if (text == null) {
			Tracer.trace("NOt to be cached.");
			return;
		}
		boolean forUserId = false;
		String[] fields = null;
		if (text.length() > 0) {
			fields = text.split(",");
			if (fields[0].equals(ServiceProtocol.USER_ID)) {
				forUserId = true;
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
		}
		CachedService cs = null;
		if (forUserId) {
			Tracer.trace("Going to cache for specific user");
			cs = this.getSessionCache(inData.getServiceName(), fields, session);
		} else {
			Tracer.trace("Going to cache in general...");
			cs = this.getCache(inData.getServiceName(), fields);
		}
		cs.cache(inData, outData);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.simplity.http.HttpCacheManager#invalidate(java.lang.String)
	 */
	@Override
	public void invalidate(String serviceName, HttpSession session) {
		if (this.allCache.remove(serviceName) != null) {
			return;
		}
		@SuppressWarnings("rawtypes")
		Map map = (Map) session.getAttribute(NAME_IN_SESSION);
		if (map != null) {
			map.remove(serviceName);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private CachedService getSessionCache(String serviceName, String[] fields,
			HttpSession session) {
		Map map = (Map) session.getAttribute(NAME_IN_SESSION);
		if (map == null) {
			/*
			 * we have to avoid concurrent creations...
			 */
			map = this.createSessionMap(session);
		}
		CachedService cs = (CachedService) map.get(serviceName);
		if (cs == null) {
			cs = new CachedService(fields);
			map.put(serviceName, cs);
		}
		return cs;
	}

	@SuppressWarnings("rawtypes")
	private synchronized Map createSessionMap(HttpSession session) {
		Map map = (Map) session.getAttribute(NAME_IN_SESSION);
		if (map == null) {
			map = new HashMap();
			session.setAttribute(NAME_IN_SESSION, map);
		}
		return map;
	}

	private CachedService getCache(String serviceName, String[] fields) {
		CachedService cs = this.allCache.get(serviceName);
		if (cs == null) {
			cs = new CachedService(fields);
			this.allCache.put(serviceName, cs);
		}
		return cs;
	}
}

/**
 * cache manager for a given service.
 *
 * @author simplity.org
 *
 */
class CachedService {
	/**
	 * null means no need to look at input. response is same.
	 */
	private final String[] fieldNames;
	/**
	 * single response iff fieldNames == null
	 */
	private String response;
	/**
	 * responses indexed by input field values
	 */
	private Map<String, String> responses;

	/**
	 * created at the first cache.
	 *
	 * @param fieldNames
	 */
	CachedService(String[] fieldNames) {
		this.fieldNames = fieldNames;
	}

	/**
	 * cache a response
	 *
	 * @param inData
	 * @param outData
	 */
	void cache(ServiceData inData, ServiceData outData) {
		if (this.fieldNames == null) {
			this.response = outData.getPayLoad();
			return;
		}
		this.responses.put(this.getInDataKey(inData.getPayLoad()),
				outData.getPayLoad());
	}

	/**
	 * retrieve a cached response
	 *
	 * @param inData
	 * @return
	 */
	String getResponse(ServiceData inData) {
		if (this.response != null) {
			return this.response;
		}
		return this.responses.get(this.getInDataKey(inData.getPayLoad()));
	}

	/**
	 * generate index key based on input field values
	 *
	 * @param text
	 * @return
	 */
	private String getInDataKey(String text) {
		JSONObject json;
		if (text == null || text.length() == 0) {
			json = new JSONObject("{}");
		} else {
			json = new JSONObject(text);
		}
		StringBuilder sbf = new StringBuilder();
		boolean firstOne = true;
		for (String nam : this.fieldNames) {
			if (firstOne) {
				firstOne = false;
			} else {
				sbf.append('\0');
			}
			Object obj = json.get(nam);
			if (obj != null) {
				sbf.append(obj);
			}
		}
		return sbf.toString();
	}
}
