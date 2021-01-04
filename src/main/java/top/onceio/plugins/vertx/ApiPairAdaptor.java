package top.onceio.plugins.vertx;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import top.onceio.core.OConfig;
import top.onceio.core.annotation.Validate;
import top.onceio.core.beans.ApiMethod;
import top.onceio.core.beans.ApiPair;
import top.onceio.core.db.annotation.Model;
import top.onceio.core.db.dao.DaoHolder;
import top.onceio.core.db.model.AccessHelper;
import top.onceio.core.db.model.BaseMeta;
import top.onceio.core.db.model.BaseModel;
import top.onceio.core.exception.Failed;
import top.onceio.core.util.OReflectUtil;
import top.onceio.core.util.OUtils;

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

    private Object[] detectInvokeArgs(final RoutingContext event) {
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
     * @param event
     */
    private Object[] resolveArgs(final RoutingContext event) {
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
        MultiMap map = event.queryParams();
        JsonObject queryJson = new JsonObject();
        for (Map.Entry<String, String> entry : map.entries()) {
            String val = entry.getValue();
            String name = entry.getKey();
            String[] ps = name.split("\\.");
            String pname = name;
            JsonObject jobj = queryJson;
            if (ps.length > 0) {
                pname = ps[ps.length - 1];
                jobj = getOrCreateFatherByPath(queryJson, ps);
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
        for (String name : queryJson.fieldNames()) {
            json.put(name, queryJson.getValue(name));
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
        Class<?>[] types = apiPair.getMethod().getParameterTypes();

        if (paramNameArgIndex != null && !paramNameArgIndex.isEmpty()) {
            for (Map.Entry<Integer, String> entry : paramNameArgIndex.entrySet()) {
                Class<?> type = types[entry.getKey()];
                if (DaoHolder.class.isAssignableFrom(apiPair.getBean().getClass())) {
                    Type t = DaoHolder.class.getTypeParameters()[0];
                    Class<?> tblClass = OReflectUtil.searchGenType(DaoHolder.class, apiPair.getBean().getClass(), t);
                    if (BaseMeta.class.isAssignableFrom(type) && args[entry.getKey()] == null) {
                        if (apiPair.getApiMethod().equals(ApiMethod.GET)) {
                            BaseMeta cnd = AccessHelper.createFindBaseMeta(tblClass, json.getMap());
                            args[entry.getKey()] = cnd;
                        } else if (apiPair.getApiMethod().equals(ApiMethod.DELETE)) {
                            BaseMeta cnd = AccessHelper.createDeleteBaseMeta(tblClass, json.getMap());
                            args[entry.getKey()] = cnd;
                        }
                    } else if (entry.getValue().equals("$page")) {
                        Object page = trans(json, "$page", Integer.TYPE);
                        args[entry.getKey()] = (page != null ? page : 1);
                    } else if (entry.getValue().equals("$pageSize")) {
                        Object pageSize = trans(json, "$pageSize", Integer.TYPE);
                        args[entry.getKey()] = (pageSize != null ? pageSize : OConfig.PAGE_SIZE_DEFAULT);
                    } else if (entry.getValue().equals("id")) {
                        Type ID = BaseModel.class.getTypeParameters()[0];
                        Class<?> idClass = OReflectUtil.searchGenType(BaseModel.class, tblClass, ID);
                        args[entry.getKey()] = trans(json, entry.getValue(), idClass);
                    } else if (entry.getValue().equals("ids")) {
                        Type ID = BaseModel.class.getTypeParameters()[0];
                        Class<?> idClass = OReflectUtil.searchGenType(BaseModel.class, tblClass, ID);
                        String idsString = json.getString(entry.getValue());
                        String[] idArray = idsString.split(",");
                        List<Object> ids = new ArrayList<>(idArray.length);
                        for (String id : idArray) {
                            Object idObj = trans(id, idClass);
                            ids.add(idObj);
                        }
                        args[entry.getKey()] = ids;
                    } else if (!entry.getValue().equals("")) {
                        args[entry.getKey()] = trans(json, entry.getValue(), type);
                    } else {
                        args[entry.getKey()] = trans(json, entry.getValue(), tblClass);
                    }
                } else {
                    args[entry.getKey()] = trans(json, entry.getValue(), type);
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
                Cookie cookie = event.getCookie(entry.getValue());
                if (cookie != null) {
                    Class<?> type = types[entry.getKey()];
                    args[entry.getKey()] = trans(cookie.getValue(), type);
                }
            }
        }
        if (headerNameArgIndex != null && !headerNameArgIndex.isEmpty()) {
            HttpServerRequest req = event.request();
            for (Map.Entry<Integer, String> entry : headerNameArgIndex.entrySet()) {
                Class<?> type = types[entry.getKey()];
                args[entry.getKey()] = trans(req.getHeader(entry.getValue()), type);
            }
        }
        if (attrNameArgIndex != null && !attrNameArgIndex.isEmpty()) {
            for (Map.Entry<Integer, String> entry : attrNameArgIndex.entrySet()) {
                Class<?> type = types[entry.getKey()];
                args[entry.getKey()] = trans(event.get(entry.getValue()), type);
            }
        }
        //TODO 表单验证
        Validate[] validates = new Validate[types.length];
        Model[] models = new Model[types.length];
        for (int i = 0; i < validates.length; i++) {
            validates[i] = apiPair.getMethod().getParameters()[i].getAnnotation(Validate.class);
            models[i] = types[i].getAnnotation(Model.class);
        }

        return args;
    }

    public Object trans(Object val, Class<?> type) {
        if (val == null) {
            return null;
        }
        if (type.equals(String.class)) {
            return val.toString();
        } else if (type.equals(int.class) || type.equals(Integer.class)) {
            return Integer.valueOf(val.toString());
        } else if (type.equals(long.class) || type.equals(Long.class)) {
            return Long.valueOf(val.toString());
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return Boolean.valueOf(val.toString());
        } else if (type.equals(byte.class) || type.equals(Byte.class)) {
            return Byte.valueOf(val.toString());
        } else if (type.equals(short.class) || type.equals(Short.class)) {
            return Short.valueOf(val.toString());
        } else if (type.equals(double.class) || type.equals(Double.class)) {
            return Double.valueOf(val.toString());
        } else if (type.equals(float.class) || type.equals(Float.class)) {
            return Float.valueOf(val.toString());
        } else if (type.equals(BigDecimal.class)) {
            return new BigDecimal(val.toString());
        } else if (type.equals(Date.class)) {
            return new Date(Long.valueOf(val.toString()));
        } else {
            return null;
        }
    }

    private Object trans(JsonObject obj, String key, Class<?> type) {
        if (obj != null) {
            if (!"".equals(key)) {
                Object val = obj.getValue(key);
                if (val == null) {
                    return val;
                } else {
                    Class<?> valType = val.getClass();
                    if (valType.equals(type)) {
                        return val;
                    } else if (type.equals(String.class)) {
                        return val.toString();
                    } else if (type.equals(int.class) || type.equals(Integer.class)) {
                        return Integer.valueOf(val.toString());
                    } else if (type.equals(long.class) || type.equals(Long.class)) {
                        return Long.valueOf(val.toString());
                    } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
                        return Boolean.valueOf(val.toString());
                    } else if (type.equals(byte.class) || type.equals(Byte.class)) {
                        return obj.getBinary(key)[0];
                    } else if (type.equals(short.class) || type.equals(Short.class)) {
                        return Short.valueOf(val.toString());
                    } else if (type.equals(double.class) || type.equals(Double.class)) {
                        return Double.valueOf(val.toString());
                    } else if (type.equals(float.class) || type.equals(Float.class)) {
                        return Float.valueOf(val.toString());
                    } else if (type.equals(BigDecimal.class)) {
                        return new BigDecimal(val.toString());
                    } else if (type.equals(Date.class)) {
                        return new Date(Long.valueOf(val.toString()));
                    } else if (valType.equals(String.class)) {
                        return Json.decodeValue((String) val, type);
                    } else {
                        JsonObject jobj = obj.getJsonObject(key);
                        if (jobj != null) {
                            return jobj.mapTo(type);
                        } else {
                            return null;
                        }
                    }
                }

            } else {
                return obj.mapTo(type);
            }
        }
        return null;
    }

    public void invoke(RoutingContext event) {
        Object obj = null;
        Object[] args = detectInvokeArgs(event);
        if (args == null) {
            args = resolveArgs(event);
        }
        HttpServerRequest req = event.request();
        MultiMap headers = req.response().headers();

        headers.set("Content-Type", "application/json; charset=UTF-8");
        String origin = req.getHeader("Origin");
        if (origin != null) {
            headers.set("Access-Control-Allow-Origin", origin);
        }
        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE");
        headers.set("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Cookie, userId, accessToken");

        Class<?> returnType = apiPair.getMethod().getReturnType();
        try {
            obj = apiPair.getMethod().invoke(apiPair.getBean(), args);
            if (!returnType.equals(void.class) && !returnType.equals(Void.class) && obj != null) {
                String s = Json.encode(obj);
                req.response().end(s);
            } else {
                req.response().end("{}");
            }
        } catch (IllegalAccessException | IllegalArgumentException e) {
            req.response().end(e.getMessage());
        } catch (InvocationTargetException e) {
            Map<String, Object> map = new HashMap<>();
            Throwable te = e.getTargetException();
            if (te instanceof Failed) {
                Failed failed = (Failed) te;
                map.put("data", failed.getData());
                map.put("args", failed.getArgs());
                map.put("format", failed.getFormat());
                map.put("ERROR", String.format(failed.getFormat(), failed.getArgs()));
            } else {
                if (te.getMessage() != null && !te.getMessage().equals("")) {
                    map.put("ERROR", te.getMessage());
                } else {
                    map.put("ERROR", te.getClass().getName());
                }
                List<String> trace = new ArrayList<>(te.getStackTrace().length);
                for (StackTraceElement ste : te.getStackTrace()) {
                    trace.add(ste.getFileName() + ":" + ste.getLineNumber() + " " + ste.getMethodName());
                }
                map.put("stacktrace", trace);
                req.response().setStatusCode(500);
                te.printStackTrace();
            }
            req.response().end(OUtils.toJson(map));
        }
    }
}
