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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * 对象级别元信息封装
 * @author Clinton Begin
 */
public interface ObjectWrapper {
  /**
   * 1.如果ObjectWrapper中封装的是普通的Bean对象，则调用相应属性的相应getter方法。
   * 2.如果封装的是集合类，则获取指定key或下标对应的value值。
   * @param prop 属性解析器对象
   * @return 获取的值
   */
  Object get(PropertyTokenizer prop);

  /**
   * 1.如果ObjectWrapper中封装的是普通的Bean对象 则调用相应属性的相应setter方法。
   * 2.如果封装的是集合类，则设置指定key或下标对应的value值。
   * @param prop 属性解析器对象
   * @param value 设置的值
   */
  void set(PropertyTokenizer prop, Object value);

  /**
   * 查找属性表达式指定的属性，第二个参数表示是否忽略属性表达式中的下划线
   * @param name 属性表达式
   * @param useCamelCaseMapping 是否开启下划线转换驼峰
   * @return
   */
  String findProperty(String name, boolean useCamelCaseMapping);

  /**
   * 查找可读属性的名称集合
   * @return
   */
  String[] getGetterNames();

  /**
   * 查找可写属性的名称集合
   * @return
   */
  String[] getSetterNames();

  /**
   * 解析属性表达式指定属性的setter方法的方法参数类型
   * @param name 属性表达式
   * @return
   */
  Class<?> getSetterType(String name);

  /**
   * 解析属性表达式指定属性的getter方法的返回值类型
   * @param name 属性表达式
   * @return
   */
  Class<?> getGetterType(String name);

  /**
   * 判断属性表达式指定属性是否有setter方法
   * @param name 属性表达式
   * @return
   */
  boolean hasSetter(String name);

  /**
   * 判断属性表达式指定属性是否有getter方法
   * @param name 属性表达式
   * @return
   */
  boolean hasGetter(String name);

  /**
   * 为属性表达式的指定属性创建相应的MetaObject对象
   * @param name
   * @param prop
   * @param objectFactory
   * @return
   */
  MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

  /**
   * 封装的对象是否为Collection类型
   * @return
   */
  boolean isCollection();

  /**
   * 调用Collection对象的add方法
   * @param element 元素
   */
  void add(Object element);

  /**
   * 调用Collection对象的addAll方法
   * @param element 集合元素
   * @param <E> 集合元素类型
   */
  <E> void addAll(List<E> element);

}
