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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * <foreach /> 标签的 SqlNode 实现类
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  /**
   * OGNL 表达式计算器对象，用于判断循环的终止条件
   */
  private final ExpressionEvaluator evaluator;
  /**
   * 迭代的集合表达式
   */
  private final String collectionExpression;
  /**
   * foreach 节点的子节点
   */
  private final SqlNode contents;
  /**
   * 在循环开始前要添加的字符串
   */
  private final String open;
  /**
   * 在循环结束后要添加的字符串
   */
  private final String close;
  /**
   * 循环过程中的分隔符
   */
  private final String separator;
  /**
   * 集合项，foreach 节点的 item 属性
   */
  private final String item;
  /**
   * 索引变量，foreach 节点的 index 属性
   */
  private final String index;
  /**
   * mybatis全局配置对象
   */
  private final Configuration configuration;

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.获取参数 map
    Map<String, Object> bindings = context.getBindings();
    //2.解析 collectionExpression 集合OGNL表达式,获取对应的实际传入sql的参数(bindings内容？？？)
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    //如果解析的集合参数没有元素，直接返回true(检测集合长度)
    if (!iterable.iterator().hasNext()) {
      return true;
    }
    boolean first = true;
    //3.在循环开始前，添加 open 字段指定的字符串到 DynamicContext 对象
    applyOpen(context);
    int i = 0;
    //4.遍历sql参数集合，根据遍历的位置和是否指定分隔符用 PrefixedContext 封装 DynamicContext
    for (Object o : iterable) {
      //5.记录原始的 context 对象
      DynamicContext oldContext = context;
      //6.创建 PrefixedContext 对象，并让 context 指向该新对象
      if (first || separator == null) {
        context = new PrefixedContext(context, "");
      } else {
        context = new PrefixedContext(context, separator);
      }
      //7.获取 context 唯一编号，从0开始，每次获取递增1，用于转换生成新的 "#{}" 占位符名称
      int uniqueNumber = context.getUniqueNumber();
      // Issue #709
      //8.如果参数集合的元素是 Map.Entry
      if (o instanceof Map.Entry) {
        @SuppressWarnings("unchecked")
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);
        applyItem(context, mapEntry.getValue(), uniqueNumber);
      } else {
        //8.如果参数元素是 其它类型
        applyIndex(context, i, uniqueNumber);
        applyItem(context, o, uniqueNumber);
      }
      //9.执行 contents 的应用，转换子节点中的“#｛｝”占位符（FilteredDynamicContext 对象封装 PrefixedContext）
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      //10.通过判断 prefix 是否已经插入，设置循环外的 first 标志
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      //11.恢复原始的 context 对象
      context = oldContext;
      i++;
    }
    //12.在循环结束后，添加 close 字段指定的字符串到 DynamicContext 对象
    applyClose(context);
    //13.移除 index 和 item 对应的绑定
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
  }

  /**
   * 为 集合参数 的 每个元素索引 在 DynamicContext.bindings 中添加绑定
   * @param context PrefixedContext对象
   * @param o 索引对象。map参数时，为map.entry的key对象；其它集合参数时，为集合索引
   * @param i DynamicContext的唯一标识符
   */
  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o);//key为 foreach节点的index属性值，value为 索引对象
      context.bind(itemizeItem(index, i), o);//key为 index属性值添加前后缀，value为 索引对象
    }
  }

  /**
   * 为 集合参数 的 每个元素 在 DynamicContext.bindings 中添加绑定
   * @param context PrefixedContext对象
   * @param o 元素对象。map参数时，为map.entry的value对象，其它集合参数时，为集合元素
   * @param i DynamicContext的唯一标识符
   */
  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o);//key 为 item，value 是集合项
      context.bind(itemizeItem(item, i), o);//为 item 添加前缀和后缀形成的key
    }
  }

  /**
   * 添加 open 属性字符串到 DynamicContext 对象
   * @param context DynamicContext 对象
   */
  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  /**
   * 添加 close 属性字符串到 DynamicContext 对象
   * @param context DynamicContext 对象
   */
  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  /**
   * 将 item 字符串拼接 ITEM_PREFIX 和 i
   * @return
   */
  private static String itemizeItem(String item, int i) {
    return ITEM_PREFIX + item + "_" + i;
  }

  /**
   * 实现子节点访问 <foreach /> 标签中的变量的替换的 DynamicContext 实现类
   */
  private static class FilteredDynamicContext extends DynamicContext {
    /**
     * 被代理 DynamicContext 对象
     */
    private final DynamicContext delegate;
    /**
     * 被代理对象 DynamicContext 对象的唯一标识 {@link DynamicContext#getUniqueNumber()}
     */
    private final int index;
    /**
     * 对应集合项的索引变量 {@link ForEachSqlNode#index}，foreach 节点的 index 属性
     */
    private final String itemIndex;
    /**
     * 对应集合项 {@link ForEachSqlNode#item}，foreach 节点的 item 属性
     */
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
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
    public String getSql() {
      return delegate.getSql();
    }

    /**
     * 将 #{item} 占位符转换成 #{_frch_item_1}，并添加到被代理对象
     * @param sql
     */
    @Override
    public void appendSql(String sql) {
      //1.创建 GenericTokenParser 解析器。用于解析#{}，使用匿名的 TokenHandler 对象
      GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
        //1.1将对 item 的访问，替换成  itemizeItem(item, index)
        String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
        //1.2将对 itemIndex 的访问，替换成 itemizeItem(itemIndex, index)
        if (itemIndex != null && newContent.equals(content)) {
          newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
        }
        //1.3返回
        return "#{" + newContent + "}";
      });

      //2.执行 GenericTokenParser 的解析，将解析结果添加到 delegate 中
      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }

  /**
   * 支持添加 <foreach /> 标签中，多个元素之间的分隔符的 DynamicContext 实现类
   */
  private class PrefixedContext extends DynamicContext {
    /**
     * 被代理 DynamicContext 对象
     */
    private final DynamicContext delegate;
    /**
     * 迭代项指定的间隔符
     */
    private final String prefix;
    /**
     * 是否已经处理过前缀（间隔符）
     */
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
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
    public void appendSql(String sql) {
      //1.如果 未应用prefix 并且 sql非空 并且 sql非空字符长度大于0
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {
        //1.1则添加 prefix 到被代理对象
        delegate.appendSql(prefix);
        //1.2标记已应用
        prefixApplied = true;
      }
      //2.添加 sql 到代理对象
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
