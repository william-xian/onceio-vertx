package top.onceio.plugins.vertx;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import top.onceio.core.annotation.Validate;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.db.annotation.Tbl;
import top.onceio.core.db.dao.DaoHolder;
import top.onceio.core.db.dao.tpl.Cnd;
import top.onceio.core.db.dao.tpl.SelectTpl;
import top.onceio.core.db.dao.tpl.UpdateTpl;
import top.onceio.core.db.tbl.OEntity;
import top.onceio.core.exception.Failed;
import top.onceio.core.util.OReflectUtil;
import top.onceio.core.util.OUtils;

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
			for (Entry<Class<?>, Integer> entry : typeIndex.entrySet()) {
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
			if (has) {
				return args;
			}
		}
		return null;
	}

	/**
	 * 根据方法参数及其注解，从req（Attr,Param,Body,Cookie)中取出数据 TODO Header
	 * 
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
		if (event.getBody().length() > 0) {
			json = event.getBodyAsJson();
		}
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
				if (DaoHolder.class.isAssignableFrom(apiPair.getBean().getClass())) {
					Type t = DaoHolder.class.getTypeParameters()[0];
					Class<?> tblClass = OReflectUtil.searchGenType(DaoHolder.class, apiPair.getBean().getClass(), t);
					if (Cnd.class.isAssignableFrom(type) && args[entry.getKey()] == null) {
						StringBuilder sb = new StringBuilder();
						String argStr = json.getString(entry.getValue());
						if (argStr != null) {
							sb.append("cnd=" + argStr);
						}
						String page = json.getString("page");
						if (page != null && !page.equals("")) {
							sb.append("&page=" + page);
						}
						String pagesize = json.getString("pagesize");
						if (pagesize != null && !pagesize.equals("")) {
							sb.append("&pagesize=" + pagesize);
						}
						String orderby = json.getString("orderby");
						if (orderby != null && !orderby.equals("")) {
							sb.append("&orderby=" + orderby);
						}
						args[entry.getKey()] = new Cnd<>(tblClass, sb.toString());
					} else if (SelectTpl.class.isAssignableFrom(type) && args[entry.getKey()] == null) {
						String argStr = json.getString(entry.getValue());
						if (argStr == null || argStr.equals("")) {
							args[entry.getKey()] = null;
						} else {
							args[entry.getKey()] = new SelectTpl<>(tblClass, argStr);
						}
					} else if (UpdateTpl.class.isAssignableFrom(type) && args[entry.getKey()] == null) {
						String argStr = json.getString(entry.getValue());
						if (argStr == null || argStr.equals("")) {
							args[entry.getKey()] = null;
						} else {
							args[entry.getKey()] = new UpdateTpl<>(tblClass, argStr);
						}
					} else if (entry.getValue().equals("id")) {
						String argStr = json.getString(entry.getValue());
						args[entry.getKey()] = Long.parseLong(argStr);
					} else if (entry.getValue().equals("ids")) {
						String argStr = json.getString(entry.getValue());
						String[] sIds = argStr.split(",");
						List<Long> ids = new ArrayList<>(sIds.length);
						for (String id : sIds) {
							ids.add(Long.parseLong(id));
						}
						args[entry.getKey()] = ids;
					} else {
						args[entry.getKey()] = trans(json, entry.getValue(), tblClass);
						if (OEntity.class.isAssignableFrom(type)) {
							String strId = json.getString("id");
							if (strId != null) {
								Long id = Long.parseLong(strId);
								if (args[entry.getKey()] != null) {
									((OEntity) args[entry.getKey()]).setId(id);
								}
							}
						}
					}
				} else {
					if (entry.getValue().equals("")) {
						args[entry.getKey()] = json.mapTo(type);
					} else {
						args[entry.getKey()] = trans(json, entry.getValue(), type);
					}

				}
			}

			if (typeIndex != null && !typeIndex.isEmpty()) {
				for (Entry<Class<?>, Integer> entry : typeIndex.entrySet()) {
					Class<?> cls = entry.getKey();
					Integer i = entry.getValue();
					args[i] = json.mapTo(cls);
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
		//TODO 表单验证
		Validate[] validates = new Validate[types.length];
		Tbl[] tbls = new Tbl[types.length];
		for(int i =0 ; i < validates.length; i++) {
			validates[i] = apiPair.getMethod().getParameters()[i].getAnnotation(Validate.class);
			tbls[i] = types[i].getAnnotation(Tbl.class);
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
				if (!"".equals(key)) {
					JsonObject jobj = obj.getJsonObject(key);
					if (jobj != null) {
						return jobj.mapTo(type);
					} else {
						return null;
					}
				} else {
					return obj.mapTo(type);
				}
			}
		}
		return null;
	}

	public void invoke(RoutingContext event) {
		Object obj = null;
		Object[] callbackableArgs = detectCallbackableArgs(event);
		if (callbackableArgs == null) {
			final Object[] args = resoveArgs(event);
			HttpServerRequest req = event.request();
			req.response().putHeader("Content-Type", "application/json;charset=utf-8");
			
			Class<?> returnType = apiPair.getMethod().getReturnType();
			try {
				obj = apiPair.getMethod().invoke(apiPair.getBean(), args);
				if (!returnType.equals(void.class) && !returnType.equals(Void.class) && obj != null) {
					req.response().end(OUtils.toJson(obj));
				} else {
					req.response().end();
				}
			} catch (IllegalAccessException | IllegalArgumentException e) {
				req.response().end(e.getMessage());
			} catch (InvocationTargetException e) {
				Map<String,Object> map = new HashMap<>();
				Throwable te = e.getTargetException();
				if(te instanceof Failed) {
					Failed failed = (Failed) te;
					map.put("data", failed.getData());
					map.put("args", failed.getArgs());
					map.put("format", failed.getFormat());
					map.put("ERROR",String.format(failed.getFormat(), failed.getArgs()));
				} else {
					if(te.getMessage() != null && !te.getMessage().equals("")) {
						map.put("ERROR",te.getMessage());	
					}else {
						map.put("ERROR", te.getClass().getName());
					}
					List<String> trace = new ArrayList<>(te.getStackTrace().length);
					for(StackTraceElement ste:te.getStackTrace()) {
						trace.add(ste.getFileName()+":"+ste.getLineNumber() + " " + ste.getMethodName());
					}
					map.put("stacktrace", trace);
					req.response().setStatusCode(500);
					te.printStackTrace();
				}
				req.response().end(OUtils.toJson(map));
			}
		} else {
			try {
				apiPair.getMethod().invoke(apiPair.getBean(), callbackableArgs);
			} catch (IllegalAccessException | IllegalArgumentException e) {

			} catch (InvocationTargetException e) {

			}
		}
	}
}
