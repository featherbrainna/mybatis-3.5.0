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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StaticSqlSource 构建器
 *
 * 负责将 SQL 语句中的 #{} 替换成相应的 ? 占位符，
 * 并获取该 ? 占位符对应的 org.apache.ibatis.mapping.ParameterMapping 对象
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {

  private static final String parameterProperties = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

  public SqlSourceBuilder(Configuration configuration) {
    super(configuration);
  }

  /**
   * 将 DynamicSqlSource 和 RawSqlSource 解析为 StaticSqlSource
   * @param originalSql 经过 sqlNode.apply()方法处理后的原始SQL语句（动态SQL经过处理后）
   * @param parameterType 用户传入的实参类型
   * @param additionalParameters 附加参数集合。可能是空集合，也可能是 DynamicContext#bindings 集合
   * @return SqlSource对象
   */
  public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    //1.创建 ParameterMappingTokenHandler 对象，它是解析 "#{}"占位符中参数属性以及替换占位符的核心
    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
    //2.使用 GenericTokenParser 和 ParameterMappingTokenHandler 配合解析 "#{}" 占位符
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    //3.执行解析
    String sql = parser.parse(originalSql);
    //4.创建 StaticSqlSource ，其中封装了占位符被替换成"?"的SQL语句以及参数对应的 ParameterMaping 集合
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
  }

  /**
   * 解析 "#{}"占位符中参数属性以及替换占位符的核心
   */
  private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {

    /**
     * 用于记录解析得到的 ParameterMapping 集合
     */
    private List<ParameterMapping> parameterMappings = new ArrayList<>();
    /**
     * 参数类型
     */
    private Class<?> parameterType;
    /**
     * DynamicContext.bindings 集合对应的 MetaObject 对象
     */
    private MetaObject metaParameters;

    public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
      super(configuration);
      this.parameterType = parameterType;
      // 创建 additionalParameters 参数的对应的 MetaObject 对象
      this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    public List<ParameterMapping> getParameterMappings() {
      return parameterMappings;
    }

    @Override
    public String handleToken(String content) {
      //1.解析参数属性映射。依据参数的表达式创建一个 ParameterMapping 对象，并添加到 parameterMappings 集合
      parameterMappings.add(buildParameterMapping(content));
      //2.将 #{XXX} 替换成 ?
      return "?";
    }

    /**
     * 解析参数属性并形成ParameterMapping
     * @param content 参数表达式
     * @return
     */
    private ParameterMapping buildParameterMapping(String content) {
      //1.解析参数表达式的的属性Map
      Map<String, String> propertiesMap = parseParameterMapping(content);
      //2.获取参数名称
      String property = propertiesMap.get("property");
      //3.获取参数的javaType属性
      Class<?> propertyType;
      if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
        propertyType = metaParameters.getGetterType(property);
      } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
        propertyType = parameterType;
      } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
        propertyType = java.sql.ResultSet.class;
      } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
        propertyType = Object.class;
      } else {
        MetaClass metaClass = MetaClass.forClass(parameterType, configuration.getReflectorFactory());
        if (metaClass.hasGetter(property)) {
          propertyType = metaClass.getGetterType(property);
        } else {
          propertyType = Object.class;
        }
      }
      //3.创建 ParameterMapping构建者 对象，并设置 ParameterMapping 相关属性
      ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
      Class<?> javaType = propertyType;
      String typeHandlerAlias = null;
      //4.遍历 propertiesMap 属性集合设置 ParameterMapping 相关属性
      for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
        //获取属性名
        String name = entry.getKey();
        //获取属性值
        String value = entry.getValue();
        //依据属性名设置属性
        if ("javaType".equals(name)) {
          javaType = resolveClass(value);
          builder.javaType(javaType);
        } else if ("jdbcType".equals(name)) {
          builder.jdbcType(resolveJdbcType(value));
        } else if ("mode".equals(name)) {
          builder.mode(resolveParameterMode(value));
        } else if ("numericScale".equals(name)) {
          builder.numericScale(Integer.valueOf(value));
        } else if ("resultMap".equals(name)) {
          builder.resultMapId(value);
        } else if ("typeHandler".equals(name)) {
          typeHandlerAlias = value;
        } else if ("jdbcTypeName".equals(name)) {
          builder.jdbcTypeName(value);
        } else if ("property".equals(name)) {
          // Do Nothing
        } else if ("expression".equals(name)) {
          throw new BuilderException("Expression based parameters are not supported yet");
        } else {
          throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + parameterProperties);
        }
      }
      //5.获取并设置 typeHandler 对象
      if (typeHandlerAlias != null) {
        builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
      }
      //6.创建 ParameterMapping 对象
      return builder.build();
    }

    /**
     * 解析参数表达式的属性成Map集合
     * @param content
     * @return
     */
    private Map<String, String> parseParameterMapping(String content) {
      try {
        return new ParameterExpression(content);
      } catch (BuilderException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
      }
    }
  }

}
