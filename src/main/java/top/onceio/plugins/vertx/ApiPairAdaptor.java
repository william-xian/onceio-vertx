package top.onceio.plugins.vertx;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.sql.Date;
import java.util.Map;
import java.util.Map.Entry;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.db.dao.Cnd;
import top.onceio.core.db.dao.DaoHolder;
import top.onceio.core.db.dao.tpl.SelectTpl;
import top.onceio.core.db.dao.tpl.UpdateTpl;
import top.onceio.core.util.OLog;
import top.onceio.core.util.OReflectUtil;

public class ApiPairAdaptor {
	private ApiPair apiPair;

	public ApiPairAdaptor(ApiPair ap) {
		this.apiPair = ap;
	}


	private static JsonObject getOrCreateFatherByPath(JsonObject json, String[] ps) {
		JsonObject jobj = json;
		for (int i = 0; i < ps.length - 1; i++) {
			String p = ps[i];
			jobj = jobj.getJsonObject(p);
			if (jobj == null) {
				jobj = new JsonObject();
				jobj.put(p, jobj);
			}
		}
		return jobj;
	}
	
	private Object[] detectCallbackableArgs(final RoutingContext event) {
		Map<Class<?>, Integer> typeIndex = apiPair.getTypeIndex();
		Method method = apiPair.getMethod();
		Object[] args = new Object[method.getParameterCount()];
		if (typeIndex != null && !typeIndex.isEmpty()) {
			boolean has = false;
			for(Entry<Class<?>, Integer> entry:typeIndex.entrySet()) {
				Class<?> cls = entry.getKey();
				Integer i = entry.getValue();
				if (RoutingContext.class.isAssignableFrom(cls)) {
					args[i] = event;
					has = true;
				} else if (HttpServerRequest.class.isAssignableFrom(cls)) {
					args[i] = event.request();
					has = true;
				}
			}
			if(has) {
				return args;
			}
		}
		return null;
	}
	
	/**
	 * 根据方法参数及其注解，从req（Attr,Param,Body,Cookie)中取出数据
	 * TODO Header
	 * @param result
	 * @param req
	 */
	private Object[] resoveArgs(final RoutingContext event) {
		Map<String, Integer> nameVarIndex = apiPair.getNameVarIndex();
		Map<Class<?>, Integer> typeIndex = apiPair.getTypeIndex();
		Method method = apiPair.getMethod();
		Object[] args = new Object[method.getParameterCount()];

		Map<Integer, String> paramNameArgIndex = apiPair.getParamNameArgIndex();
		Map<Integer, String> cookieNameArgIndex = apiPair.getCookieNameArgIndex();
		Map<Integer, String> headerNameArgIndex = apiPair.getHeaderNameArgIndex();
		Map<Integer, String> attrNameArgIndex = apiPair.getAttrNameArgIndex();
		
		JsonObject json = null;
		json = event.getBodyAsJson();
		if (json == null) {
			json = new JsonObject();
		}
		String uri = event.normalisedPath();
		try {
			uri = URLDecoder.decode(uri, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		String[] uris = uri.split("/");
		for (String name : nameVarIndex.keySet()) {
			Integer i = nameVarIndex.get(name);
			String v = uris[i];
			json.put(name, v);
		}
		MultiMap map = event.queryParams();
		for (Map.Entry<String, String> entry : map.entries()) {
			String val = entry.getValue();
			String name = entry.getKey();
			String[] ps = name.split("\\.");
			String pname = name;
			JsonObject jobj = json;
			if (ps.length > 0) {
				pname = ps[ps.length - 1];
				jobj = getOrCreateFatherByPath(json, ps);
			}

			Object jval = jobj.getValue(pname);
			if (jval == null) {
				jobj.put(pname, val);
			} else {
				if (jval instanceof JsonArray) {
					((JsonArray) jval).add(val);
				} else {
					JsonArray ja = new JsonArray();
					ja.add(jval);
					ja.add(val);
					jobj.put(pname, ja);
				}
			}
		}
		Class<?>[] types = apiPair.getMethod().getParameterTypes();

		if (paramNameArgIndex != null && !paramNameArgIndex.isEmpty()) {
			for (Map.Entry<Integer, String> entry : paramNameArgIndex.entrySet()) {
				Class<?> type = types[entry.getKey()];
				if (entry.getValue().equals("")) {
					args[entry.getKey()] = json.mapTo(type);
				} else {
					if (DaoHolder.class.isAssignableFrom(apiPair.getBean().getClass())) {
						Type t = DaoHolder.class.getTypeParameters()[0];
						Class<?> tblClass = OReflectUtil.searchGenType(DaoHolder.class, apiPair.getBean().getClass(),
								t);
						String argStr = json.getString(entry.getValue());
						if (type.isAssignableFrom(Cnd.class) && args[entry.getKey()] == null) {
							args[entry.getKey()] = new Cnd<>(tblClass, argStr);
						} else if (type.isAssignableFrom(SelectTpl.class) && args[entry.getKey()] == null) {
							args[entry.getKey()] = new SelectTpl<>(tblClass, argStr);
						} else if (type.isAssignableFrom(UpdateTpl.class) && args[entry.getKey()] == null) {
							args[entry.getKey()] = new UpdateTpl<>(tblClass, argStr);
						} else {
							args[entry.getKey()] = trans(json, entry.getValue(), type);
						}
					} else {
						args[entry.getKey()] = trans(json, entry.getValue(), type);
					}
				}
			}
			
			if (typeIndex != null && !typeIndex.isEmpty()) {
				for(Entry<Class<?>, Integer> entry:typeIndex.entrySet()) {
					Class<?> cls = entry.getKey();
					Integer i = entry.getValue();
					args[i] = json.mapTo(cls);;
				}
				
			}
		}
		if (cookieNameArgIndex != null && !cookieNameArgIndex.isEmpty()) {
			for (Map.Entry<Integer, String> entry : cookieNameArgIndex.entrySet()) {
				args[entry.getKey()] = event.getCookie(entry.getValue());
			}
		}
		if (headerNameArgIndex != null && !headerNameArgIndex.isEmpty()) {
			HttpServerRequest req = event.request();
			for (Map.Entry<Integer, String> entry : headerNameArgIndex.entrySet()) {
				args[entry.getKey()] = req.getHeader(entry.getValue());
			}
		}
		if (attrNameArgIndex != null && !attrNameArgIndex.isEmpty()) {
			for (Map.Entry<Integer, String> entry : attrNameArgIndex.entrySet()) {
				args[entry.getKey()] = event.get(entry.getValue());
			}
		}
		return args;
	}

	private Object trans(JsonObject obj, String key, Class<?> type) {
		if (obj != null) {
			if (type.equals(String.class)) {
				return obj.getString(key);
			} else if (type.equals(int.class) || type.equals(Integer.class)) {
				return obj.getInteger(key);
			} else if (type.equals(long.class) || type.equals(Long.class)) {
				return obj.getLong(key);
			} else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
				return obj.getBoolean(key);
			} else if (type.equals(byte.class) || type.equals(Byte.class)) {
				return obj.getBinary(key)[0];
			} else if (type.equals(short.class) || type.equals(Short.class)) {
				return obj.getInteger(key);
			} else if (type.equals(double.class) || type.equals(Double.class)) {
				return obj.getDouble(key);
			} else if (type.equals(float.class) || type.equals(Float.class)) {
				return obj.getFloat(key);
			} else if (type.equals(BigDecimal.class)) {
				return new BigDecimal(obj.getString(key));
			} else if (type.equals(Date.class)) {
				return new Date(obj.getLong(key));
			} else {
				return obj.getJsonObject(key).mapTo(type);
			}
		}
		return null;
	}

	public void invoke(RoutingContext event)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Object[] callbackableArgs = detectCallbackableArgs(event);
		if(callbackableArgs == null) {
			final Object[] args = resoveArgs(event);
			HttpServerRequest req = event.request();
			req.bodyHandler(new Handler<Buffer>() {
				@Override
				public void handle(Buffer event) {
					req.response().putHeader("Content-Type", "application/json");
					Object obj = null;
					String msg = null;
					Class<?> returnType = apiPair.getMethod().getReturnType();
					try {
						obj = apiPair.getMethod().invoke(apiPair.getBean(), args);
					} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
						msg = e.getMessage();
					}
					if(!returnType.equals(void.class) && !returnType.equals(Void.class)) {
						if (obj != null) {
							req.response().end(Json.encode(obj));
						} else {
							req.response().end(msg);
						}	
					}
					
				}
			});
			req.exceptionHandler(handler -> {
				OLog.error(handler.getMessage());
			});
		} else {
			apiPair.getMethod().invoke(apiPair.getBean(), callbackableArgs);
		}

	}
}
