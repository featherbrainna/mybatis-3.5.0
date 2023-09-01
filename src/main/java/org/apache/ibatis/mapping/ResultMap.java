/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.mapping;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * 结果映射。解析 resultMap 节点生成的对象
 * @author Clinton Begin
 */
public class ResultMap {
  /**
   * Configuration对象
   */
  private Configuration configuration;

  /**
   * resultMap 节点的 id 属性
   */
  private String id;
  /**
   * resultMap 节点的type属性
   */
  private Class<?> type;
  /**
   * 记录了除 discriminator 节点之外的其他映射关系
   */
  private List<ResultMapping> resultMappings;
  /**
   * 记录了 id 标签的映射关系，例如 id 节点和 constructor 节点的 idArg 子节点
   */
  private List<ResultMapping> idResultMappings;
  /**
   * 记录了 constructor 标志的映射关系，例如所有 constructor 节点
   */
  private List<ResultMapping> constructorResultMappings;
  /**
   * 记录了不带 constructor 标志（一个在ResultMapping聚合的枚举类）的映射关系
   */
  private List<ResultMapping> propertyResultMappings;
  /**
   * 记录映射关系中所有涉及的 column 属性集合
   */
  private Set<String> mappedColumns;
  /**
   * 记录映射关系中所有涉及的 property 属性集合
   */
  private Set<String> mappedProperties;
  /**
   * 鉴别器，对应 discriminator 节点
   */
  private Discriminator discriminator;
  /**
   * 是否有嵌套的结果映射，如果某个映射关系中存在 resultMap 属性，且不存在 resultset属性，则为true
   */
  private boolean hasNestedResultMaps;
  /**
   * 是否有嵌套select查询，如果某个映射关系存在 select 属性，则为true
   */
  private boolean hasNestedQueries;
  /**
   * 是否开启自动映射
   */
  private Boolean autoMapping;

  /**
   * 私有化构造器，由 {@link Builder} 来构建本类对象
   */
  private ResultMap() {
  }

  /**
   * ResultMap 的构建器，类似构建者模式
   */
  public static class Builder {
    /**
     * 单例日志对象，初始化类时生成，饿汉模式
     */
    private static final Log log = LogFactory.getLog(Builder.class);

    /**
     * 记录 ResultMap 对象
     */
    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    /**
     * 构造器，初始化 configuration、id、type、resultMappings、autoMapping 属性
     * @param configuration
     * @param id
     * @param type
     * @param resultMappings
     * @param autoMapping
     */
    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;
      resultMap.id = id;
      resultMap.type = type;
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    /**
     * 设置 discriminator 属性
     * @param discriminator
     * @return
     */
    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    /**
     * 设置 type 属性
     * @return
     */
    public Class<?> type() {
      return resultMap.type;
    }

    /**
     * 核心构建最终 ResultMap 对象方法
     * @return
     */
    public ResultMap build() {
      //1.id属性为空，则抛出异常
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      //2.mappedColumns、mappedProperties、idResultMappings、constructorResultMappings、propertyResultMappings属性初始化
      resultMap.mappedColumns = new HashSet<>();
      resultMap.mappedProperties = new HashSet<>();
      resultMap.idResultMappings = new ArrayList<>();
      resultMap.constructorResultMappings = new ArrayList<>();
      resultMap.propertyResultMappings = new ArrayList<>();
      final List<String> constructorArgNames = new ArrayList<>();
      //3.遍历 resultMappings 属性的集合对象
      for (ResultMapping resultMapping : resultMap.resultMappings) {
        //4.通过 映射关系 初始化 hasNestedQueries、hasNestedResultMaps 属性
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
        //5.获取映射关系的 column 属性
        final String column = resultMapping.getColumn();
        //6.如果 column 属性不为空，则大写字母记录到 mappedColumns 属性集合
        if (column != null) {
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
        } else if (resultMapping.isCompositeResult()) {
          //6.如果 映射关系 的 column 属性为空且 composites 属性不为空且有元素（处理嵌套的 column 属性）
          //6.1遍历composites属性下的 子映射关系
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            //6.2获取 子映射关系 的 column 属性
            final String compositeColumn = compositeResultMapping.getColumn();
            //6.3如果 子映射关系 的 column 属性不为空，则大写字母记录到 mappedColumns 属性集合
            //【注意这里只能嵌套一层处理 column 属性】
            if (compositeColumn != null) {
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }
        //7.获取 映射关系 的 property 属性，并添加到 mappedProperties 属性集合
        final String property = resultMapping.getProperty();
        if(property != null) {
          resultMap.mappedProperties.add(property);
        }
        //8.如果 映射关系 有 constructor 标志，则将映射关系添加到 constructorResultMappings 属性集合
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          //8.如果 映射关系 没有 constructor 标志，则将映射关系添加到 propertyResultMappings 属性集合
          resultMap.propertyResultMappings.add(resultMapping);
        }
        //9.如果 映射关系 有 id 标志，则将映射关系添加到 idResultMappings 属性集合
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      //10.如果 idResultMappings 属性为空集合，则添加所有 resultMappings 属性中的元素
      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }
      //11.如果 constructorArgNames 集合不为空
      if (!constructorArgNames.isEmpty()) {
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        //如果 actualArgNames 为空抛出异常
        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        //排序 constructorResultMappings 集合
        Collections.sort(resultMap.constructorResultMappings, (o1, o2) -> {
          int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
          int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
          return paramIdx1 - paramIdx2;
        });
      }
      // lock down collections
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) {
          List<String> paramNames = getArgNames(constructor);
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      return null;
    }

    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<>();
      List<String> actualParamNames = null;
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }
        if (name == null && resultMap.configuration.isUseActualParamName()) {
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }

  public Boolean getAutoMapping() {
    return autoMapping;
  }

}
