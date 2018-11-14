package cn.ouctechnology.oodb.beautiful.session;


import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @program: oodb
 * @author: ZQX
 * @create: 2018-11-12 15:17
 * @description: TODO
 **/
public class Query {
    private String oql;
    private Session session;

    public Query(String oql, Session session) {
        this.oql = oql;
        this.session = session;
    }

    public String getOql() {
        return oql;
    }

    public List list() {
        session.sendMessage(oql);
        Object response = session.getResponse();
        return (List) response;
    }

    public int update() {
        session.sendMessage(oql);
        String response = (String) session.getResponse();
        String rows = response.substring(0, response.indexOf(" "));
        return Integer.parseInt(rows);
    }

    public Object firstResult() {
        Map<String, Object> firstMap = (Map<String, Object>) list().get(0);
        return firstMap.entrySet().iterator().next().getValue();
    }

    public <T> T firstResult(Class<T> clz) {
        List<T> list = list(clz);
        if (list == null || list.size() == 0) return null;
        return list.get(0);
    }

    public <T> List<T> list(Class<T> clz) {
        //获取list对象
        List<Map<String, Object>> resMaps = list();
        //获取setter方法
        return resMaps.stream().map(r -> {
            Map<String, Object> newMap = new HashMap<>();
            r.forEach((k, v) -> {
                newMap.put(k.substring(k.indexOf(".") + 1), v);
            });
            return populate(clz, newMap);
        }).collect(Collectors.toList());
    }

    private <T> T populate(Class<T> clz, Map<String, Object> newMap) {
        try {
            T obj = clz.newInstance();
            BeanInfo beanInfo = Introspector.getBeanInfo(clz);
            PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
            for (PropertyDescriptor pd : pds) {
                Method writeMethod = pd.getWriteMethod();
                if (writeMethod == null) continue;
                String name = pd.getName();
                Object value = newMap.get(name);
                if (value instanceof Map) {
                    Object subObj = populate(pd.getPropertyType(), (Map<String, Object>) value);
                    writeMethod.invoke(obj, subObj);
                } else {
                    writeMethod.invoke(obj, value);
                }
            }
            return obj;
        } catch (IntrospectionException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }
}
