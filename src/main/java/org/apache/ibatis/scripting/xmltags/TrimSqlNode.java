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

import org.apache.ibatis.session.Configuration;

import java.util.*;

/**
 * <trim /> 标签的 SqlNode 实现类（树枝节点）
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

  /**
   * trim 节点内嵌的 sqlNode 子节点
   */
  private final SqlNode contents;
  /**
   * trim节点要添加的前缀
   */
  private final String prefix;
  /**
   * trim节点要添加的后缀
   */
  private final String suffix;
  /**
   * trim节点需要被删除的前缀（除prefix指定的属性）
   */
  private final List<String> prefixesToOverride;
  /**
   * trim节点需要被删除的前缀（除suffix指定的属性）
   */
  private final List<String> suffixesToOverride;
  /**
   * mybatis全局配置对象
   */
  private final Configuration configuration;

  /**
   * 对外构造器
   */
  public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride, String suffix, String suffixesToOverride) {
    this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
  }

  /**
   * 底层构造器，初始化属性
   * 由 {@link #TrimSqlNode(Configuration, SqlNode, String, String, String, String)} 调用
   * 由 {@link WhereSqlNode#WhereSqlNode(Configuration, SqlNode)} 调用
   * 由 {@link SetSqlNode#SetSqlNode(Configuration, SqlNode)} 调用
   */
  protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
    this.contents = contents;
    this.prefix = prefix;
    this.prefixesToOverride = prefixesToOverride;
    this.suffix = suffix;
    this.suffixesToOverride = suffixesToOverride;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.创建 FilteredDynamicContext 对象，其中封装了 DynamicContext（扩展DynamicContext功能，在添加前处理前后缀）
    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
    //2.执行 trim 节点子节点的应用
    boolean result = contents.apply(filteredDynamicContext);
    //3.使用 filteredDynamicContext.applyAll() 方法处理前缀和后缀
    filteredDynamicContext.applyAll();
    return result;
  }

  /**
   * 解析 trim节点 XXXOverrides 属性
   * 使用 | 分隔字符串成字符串数组，并都转换成大写
   * @param overrides
   * @return
   */
  private static List<String> parseOverrides(String overrides) {
    if (overrides != null) {
      //1.使用 StringTokenizer 来分隔字符串，分隔字符为 "|"
      final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
      //2.初始化分隔后 字符串集合 结果
      final List<String> list = new ArrayList<>(parser.countTokens());
      //3.从 StringTokenizer 对象中获取分隔结果添加到集合，并且结果字符串大写
      while (parser.hasMoreTokens()) {
        list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
      }
      return list;
    }
    return Collections.emptyList();
  }

  /**
   * TrimSqlNode 的内部类，继承 DynamicContext 类，支持 trim 逻辑的 DynamicContext 实现类。
   *
   * 每个实现DynamicContext的方法，都直接调用 delegate 对应的方法。除了 #append(String sql) 方法以外。
   */
  private class FilteredDynamicContext extends DynamicContext {
    /**
     * 底层委托的 DynamicContext
     */
    private DynamicContext delegate;
    /**
     * 标记 prefix 是否已经被应用
     */
    private boolean prefixApplied;
    /**
     * 标记 suffix 是否已经被应用
     */
    private boolean suffixApplied;
    /**
     * StringBuilder 对象
     * 用于记录 trim 子节点应用后的结果，FilteredDynamicContext.appendSql()方法会向该字段添加应用结果
     * 而不是调用 delegate.appendSql() 方法
     */
    private StringBuilder sqlBuffer;

    public FilteredDynamicContext(DynamicContext delegate) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefixApplied = false;
      this.suffixApplied = false;
      this.sqlBuffer = new StringBuilder();
    }

    /**
     * 处理前后缀，并添加到 被代理对象 的 DynamicContext.sqlBuilder 中
     */
    public void applyAll() {
      //1.去除掉 sqlBuffer 属性多余的空格，并生成新的 sqlBuffer
      sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
      //2.将 sqlBuffer 全部大写，生成 trimmedUppercaseSql 字符串。（为了对比要删除的前缀）
      String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
      if (trimmedUppercaseSql.length() > 0) {
        //3.处理前缀
        applyPrefix(sqlBuffer, trimmedUppercaseSql);
        //4.处理后缀
        applySuffix(sqlBuffer, trimmedUppercaseSql);
      }
      //5.将结果，添加到 delegate 中
      delegate.appendSql(sqlBuffer.toString());
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

    /**
     * 特别的，应用子节点时添加 sql 到 sqlBuffer 属性（本对象属性），而不是代理对象中
     * sqlBuffer 属性缓存这些应用的子节点 sql，在 applyAll() 调用时处理前后缀后最终添加到代理对象
     * @param sql
     */
    @Override
    public void appendSql(String sql) {
      sqlBuffer.append(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    /**
     * 处理前缀
     * @param sql sqlBuffer属性对象
     * @param trimmedUppercaseSql sqlBuffer属性大写字符串
     */
    private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
      //1.检测是否已经处理过前缀，未处理则进行处理
      if (!prefixApplied) {
        //标记已处理
        prefixApplied = true;
        //2.如果 prefixesToOverride 属性非空，先删除指定前缀
        if (prefixesToOverride != null) {
          //2.1遍历集合删除
          for (String toRemove : prefixesToOverride) {
            //2.2如果 trimmedUppercaseSql 字符串以某项开头，则将该项从SQL语句开头删除
            if (trimmedUppercaseSql.startsWith(toRemove)) {
              sql.delete(0, toRemove.trim().length());
              break;
            }
          }
        }
        //3.如果 prefix 属性非空，则添加前缀
        if (prefix != null) {
          sql.insert(0, " ");
          sql.insert(0, prefix);
        }
      }
    }

    /**
     * 处理后缀
     * @param sql sqlBuffer属性对象
     * @param trimmedUppercaseSql sqlBuffer属性大写字符串
     */
    private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
      //1.检测是否已经处理过后缀，未处理则进行处理
      if (!suffixApplied) {
        suffixApplied = true;
        //2.如果 suffixesToOverride 属性非空，先删除指定后缀
        if (suffixesToOverride != null) {
          //2.1遍历集合删除
          for (String toRemove : suffixesToOverride) {
            //2.2如果 trimmedUppercaseSql 字符串以某项结尾，则将该项从SQL语句结尾删除
            if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
              int start = sql.length() - toRemove.trim().length();
              int end = sql.length();
              sql.delete(start, end);
              break;
            }
          }
        }
        //3.如果 prefix 属性非空，则添加后缀
        if (suffix != null) {
          sql.append(" ");
          sql.append(suffix);
        }
      }
    }

  }

}
