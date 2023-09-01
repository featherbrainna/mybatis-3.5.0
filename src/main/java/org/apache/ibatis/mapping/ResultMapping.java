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

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 映射关系。解析 resultMap 节点中的 result|association|collection|constructor|id（除discriminator） 节点生成的对象
 * @author Clinton Begin
 */
public class ResultMapping {

  /**
   * Configuration对象
   */
  private Configuration configuration;
  /**
   * 对应节点的 property 属性，表示的是与该列进行映射的 java对象属性
   */
  private String property;
  /**
   * 对应节点的 column 属性，表示的是与该列进行映射的 数据库表头
   */
  private String column;
  /**
   * 对应节点的 javaType 属性，表示结果集列映射的java类型
   */
  private Class<?> javaType;
  /**
   * 对应节点的 jdbcType 属性，表示结果集列的jdbc类型
   */
  private JdbcType jdbcType;
  /**
   * 对应节点的 typeHandler 属性，表示类型处理器，它会覆盖默认的类型处理器
   */
  private TypeHandler<?> typeHandler;
  /**
   * 对应节点的 resultMap 属性，引用了嵌套的 resultMap 对象。
   * 可以使用关联查询，然后直接映射成多个对象，并同时设置这些对象之间的组合关系
   */
  private String nestedResultMapId;
  /**
   * 对应节点的 select 属性，引用了嵌套的 select语句
   */
  private String nestedQueryId;
  /**
   * 对应节点的 notNullColumn 属性
   */
  private Set<String> notNullColumns;
  /**
   * 对应节点的 columnPrefix 属性
   */
  private String columnPrefix;
  /**
   * 处理后的标志，标志共两个：id和constructor
   */
  private List<ResultFlag> flags;
  /**
   * 对应节点的 column 属性拆分后生成的结果，composites.size()>0会使column为null
   */
  private List<ResultMapping> composites;
  /**
   * 对应节点的 resultSet 属性
   */
  private String resultSet;
  /**
   * 对应节点的 foreignColumn 属性
   */
  private String foreignColumn;
  /**
   * 对应节点的 fetchType属性，是否延迟加载
   */
  private boolean lazy;

  /**
   * 构造器，无初始化动作
   */
  ResultMapping() {
  }

  /**
   * ResultMapping 的构建器，类似构建者模式
   */
  public static class Builder {
    /**
     * 记录 ResultMapping 对象
     */
    private ResultMapping resultMapping = new ResultMapping();

    public Builder(Configuration configuration, String property, String column, TypeHandler<?> typeHandler) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.typeHandler = typeHandler;
    }

    public Builder(Configuration configuration, String property, String column, Class<?> javaType) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.javaType = javaType;
    }

    /**
     * 构造器，初始化 resultMapping 的 configuration和property 属性
     * @param configuration
     * @param property
     */
    public Builder(Configuration configuration, String property) {
      resultMapping.configuration = configuration;
      resultMapping.property = property;
      resultMapping.flags = new ArrayList<>();
      resultMapping.composites = new ArrayList<>();
      resultMapping.lazy = configuration.isLazyLoadingEnabled();
    }

    //###################################### 设置resultMapping属性 ##################################################

    /**
     * 设置 resultMapping 属性的 javaType 属性
     * @param javaType
     * @return
     */
    public Builder javaType(Class<?> javaType) {
      resultMapping.javaType = javaType;
      return this;
    }

    /**
     * 设置 resultMapping 属性的 jdbcType 属性
     * @param jdbcType
     * @return
     */
    public Builder jdbcType(JdbcType jdbcType) {
      resultMapping.jdbcType = jdbcType;
      return this;
    }

    public Builder nestedResultMapId(String nestedResultMapId) {
      resultMapping.nestedResultMapId = nestedResultMapId;
      return this;
    }

    public Builder nestedQueryId(String nestedQueryId) {
      resultMapping.nestedQueryId = nestedQueryId;
      return this;
    }

    public Builder resultSet(String resultSet) {
      resultMapping.resultSet = resultSet;
      return this;
    }

    public Builder foreignColumn(String foreignColumn) {
      resultMapping.foreignColumn = foreignColumn;
      return this;
    }

    public Builder notNullColumns(Set<String> notNullColumns) {
      resultMapping.notNullColumns = notNullColumns;
      return this;
    }

    public Builder columnPrefix(String columnPrefix) {
      resultMapping.columnPrefix = columnPrefix;
      return this;
    }

    public Builder flags(List<ResultFlag> flags) {
      resultMapping.flags = flags;
      return this;
    }

    public Builder typeHandler(TypeHandler<?> typeHandler) {
      resultMapping.typeHandler = typeHandler;
      return this;
    }

    public Builder composites(List<ResultMapping> composites) {
      resultMapping.composites = composites;
      return this;
    }

    public Builder lazy(boolean lazy) {
      resultMapping.lazy = lazy;
      return this;
    }

    /**
     * 构建最终 ResultMapping 对象方法
     * @return ResultMapping 对象
     */
    public ResultMapping build() {
      // lock down collections
      resultMapping.flags = Collections.unmodifiableList(resultMapping.flags);
      resultMapping.composites = Collections.unmodifiableList(resultMapping.composites);
      //1.初始化 typeHandler 属性
      resolveTypeHandler();
      //2.验证映射关系是否编写正确
      validate();
      return resultMapping;
    }

    /**
     * 验证映射关系是否合法、编写正确
     */
    private void validate() {
      // Issue #697: cannot define both nestedQueryId and nestedResultMapId
      //1.是否同时设置了 nestedQueryId 和 nestedResultMapId 属性，即嵌套的select属性和嵌套的结果映射属性
      //同时设置了就抛出异常
      if (resultMapping.nestedQueryId != null && resultMapping.nestedResultMapId != null) {
        throw new IllegalStateException("Cannot define both nestedQueryId and nestedResultMapId in property " + resultMapping.property);
      }
      // Issue #5: there should be no mappings without typehandler
      //2.没有嵌套情况下，是否解析出设置了typeHandler属性
      //没有typeHandler属性则抛出异常
      if (resultMapping.nestedQueryId == null && resultMapping.nestedResultMapId == null && resultMapping.typeHandler == null) {
        throw new IllegalStateException("No typehandler found for property " + resultMapping.property);
      }
      // Issue #4 and GH #39: column is optional only in nested resultmaps but not in the rest
      //3.只有嵌套的结果映射，才能不设置 column 属性，其他情况抛出异常
      if (resultMapping.nestedResultMapId == null && resultMapping.column == null && resultMapping.composites.isEmpty()) {
        throw new IllegalStateException("Mapping is missing column attribute for property " + resultMapping.property);
      }
      //4.外键映射是否合法，即 resultSet 属性、 column 属性和 foreignColumn 属性是否对应
      if (resultMapping.getResultSet() != null) {
        int numColumns = 0;
        if (resultMapping.column != null) {
          numColumns = resultMapping.column.split(",").length;
        }
        int numForeignColumns = 0;
        if (resultMapping.foreignColumn != null) {
          numForeignColumns = resultMapping.foreignColumn.split(",").length;
        }
        if (numColumns != numForeignColumns) {
          throw new IllegalStateException("There should be the same number of columns and foreignColumns in property " + resultMapping.property);
        }
      }
    }

    /**
     * 通过 configuration 解析并初始化 resultMapping 属性的 typeHandler 属性
     */
    private void resolveTypeHandler() {
      if (resultMapping.typeHandler == null && resultMapping.javaType != null) {
        Configuration configuration = resultMapping.configuration;
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        resultMapping.typeHandler = typeHandlerRegistry.getTypeHandler(resultMapping.javaType, resultMapping.jdbcType);
      }
    }

    public Builder column(String column) {
      resultMapping.column = column;
      return this;
    }
  }

  public String getProperty() {
    return property;
  }

  public String getColumn() {
    return column;
  }

  public Class<?> getJavaType() {
    return javaType;
  }

  public JdbcType getJdbcType() {
    return jdbcType;
  }

  public TypeHandler<?> getTypeHandler() {
    return typeHandler;
  }

  public String getNestedResultMapId() {
    return nestedResultMapId;
  }

  public String getNestedQueryId() {
    return nestedQueryId;
  }

  public Set<String> getNotNullColumns() {
    return notNullColumns;
  }

  public String getColumnPrefix() {
    return columnPrefix;
  }

  public List<ResultFlag> getFlags() {
    return flags;
  }

  public List<ResultMapping> getComposites() {
    return composites;
  }

  public boolean isCompositeResult() {
    return this.composites != null && !this.composites.isEmpty();
  }

  public String getResultSet() {
    return this.resultSet;
  }

  public String getForeignColumn() {
    return foreignColumn;
  }

  public void setForeignColumn(String foreignColumn) {
    this.foreignColumn = foreignColumn;
  }

  public boolean isLazy() {
    return lazy;
  }

  public void setLazy(boolean lazy) {
    this.lazy = lazy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ResultMapping that = (ResultMapping) o;

    if (property == null || !property.equals(that.property)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    if (property != null) {
      return property.hashCode();
    } else if (column != null) {
      return column.hashCode();
    } else {
      return 0;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ResultMapping{");
    //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
    sb.append("property='").append(property).append('\'');
    sb.append(", column='").append(column).append('\'');
    sb.append(", javaType=").append(javaType);
    sb.append(", jdbcType=").append(jdbcType);
    //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
    sb.append(", nestedResultMapId='").append(nestedResultMapId).append('\'');
    sb.append(", nestedQueryId='").append(nestedQueryId).append('\'');
    sb.append(", notNullColumns=").append(notNullColumns);
    sb.append(", columnPrefix='").append(columnPrefix).append('\'');
    sb.append(", flags=").append(flags);
    sb.append(", composites=").append(composites);
    sb.append(", resultSet='").append(resultSet).append('\'');
    sb.append(", foreignColumn='").append(foreignColumn).append('\'');
    sb.append(", lazy=").append(lazy);
    sb.append('}');
    return sb.toString();
  }

}
