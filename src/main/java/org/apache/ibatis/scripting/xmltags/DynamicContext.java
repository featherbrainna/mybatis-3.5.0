/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态 SQL ，用于每次执行 SQL 操作时，记录动态 SQL 处理后的最终 SQL 字符串。
 * @author Clinton Begin
 */
public class DynamicContext {

  /**
   * {@link #bindings} _parameter 的键，参数
   */
  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  /**
   * {@link #bindings} _databaseId 的键，数据库编号
   */
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    // 设置 OGNL 的属性访问器
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  /**
   * 上下文的参数集合
   */
  private final ContextMap bindings;
  /**
   * 解析后的SQL
   */
  private final StringBuilder sqlBuilder = new StringBuilder();
  /**
   * 唯一编号。在 @link org.apache.ibatis.scripting.xmltags.XMLScriptBuilder.ForEachHandler 使用
   */
  private int uniqueNumber = 0;

  /**
   * 构造器
   * 1.RawSqlSource 中调用无参数
   * 2.DynamicSqlSource 中调用有参数是执行方法时传入，每次执行 sql 创建一次新的 DynamicContext 对象！！！
   * @param configuration mybatis全局Configuration对象
   * @param parameterObject 参数对象(方法为一个参数时，类型为参数元素类型；方法为多个参数时，类型为Map)
   */
  public DynamicContext(Configuration configuration, Object parameterObject) {
    //1.如果参数对象非空且不是Map类型
    //TODO ???
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      //1.1创建 parameterObject 对应的 MetaObject 对象
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      //1.2使用 metaObject 对象创建 ContextMap 对象，并初始化 bindings 属性
      bindings = new ContextMap(metaObject);
    } else {
      //2.参数为空或者参数为Map类型，则直接创建 ContextMap 对象，并初始化 bindings 属性
      bindings = new ContextMap(null);
    }
    //3.添加 bindings 的默认值
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
  }

  //##################################### bindings属性相关方法 #######################################################

  public Map<String, Object> getBindings() {
    return bindings;
  }

  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  //######################################### sqlBuilder属性相关方法 ########################################################

  public void appendSql(String sql) {
    sqlBuilder.append(sql);
    sqlBuilder.append(" ");
  }

  public String getSql() {
    return sqlBuilder.toString().trim();
  }

  /**
   * uniqueNumber属性自增返回方法
   * @return
   */
  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  /**
   * 是 DynamicContext 的内部静态类，继承 HashMap 类，上下文的参数集合
   * 该类在 HashMap 的基础上，增加支持对 parameterMetaObject 属性的访问。
   */
  static class ContextMap extends HashMap<String, Object> {
    private static final long serialVersionUID = 2977601501966151582L;

    /**
     * 非 Map 类型的参数会初始化这个属性
     * 将用户传入的参数封装成 MetaObject 对象，在构造器初始化
     */
    private MetaObject parameterMetaObject;
    public ContextMap(MetaObject parameterMetaObject) {
      this.parameterMetaObject = parameterMetaObject;
    }

    /**
     * 获取 map 中指定 key 的 value
     * @param key 指定key
     * @return
     */
    @Override
    public Object get(Object key) {
      //1.如果 ContextMap 包含指定 key ,则直接返回 value
      String strKey = (String) key;
      if (super.containsKey(strKey)) {
        return super.get(strKey);
      }

      //2.从 parameterMetaObject 中，获取 key 对应的属性
      if (parameterMetaObject != null) {
        // issue #61 do not modify the context when reading
        return parameterMetaObject.getValue(strKey);
      }

      return null;
    }
  }

  /**
   * 是 DynamicContext 的内部静态类，实现 ognl.PropertyAccessor 接口，上下文访问器
   */
  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name) {
      Map map = (Map) target;

      //1.优先从 ContextMap 中获取属性
      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      //2.如果没有，则从 PARAMETER_OBJECT_KEY 对应的 Map 中，获得属性
      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map)parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value) {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}