/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * 属性表达式的解析器。便于快速获取表达式信息
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  /**
   * 当前表达式的名称
   */
  private String name;
  /**
   * 当前表达式的索引名
   */
  private final String indexedName;
  /**
   * 索引下标
   */
  private String index;
  /**
   * 子表达式
   */
  private final String children;

  /**
   * 构造器分析fullname表达式，并初始化上面的字段
   * @param fullname 表达式
   */
  public PropertyTokenizer(String fullname) {
    //获取表达式分隔符下标
    int delim = fullname.indexOf('.');
    //1.解析当前表达式
    //有分隔符
    if (delim > -1) {
      //当前表达式
      name = fullname.substring(0, delim);
      //子表达式，初始化children
      children = fullname.substring(delim + 1);
    } else {
      //当前表达式
      name = fullname;
      children = null;
    }
    //带索引的表达式名，初始化indexedName
    indexedName = name;
    //2.解析表达式索引
    //解析表达式的索引
    delim = name.indexOf('[');
    if (delim > -1) {
      //表达式索引，初始化index
      index = name.substring(delim + 1, name.length() - 1);
      //排除索引后真正的当前表达式名，初始化name
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  /**
   * 迭代处理子表达式
   * @return 子表达式对应的PropertyTokenizer对象
   */
  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  /**
   * 不支持删除
   */
  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
