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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;
import java.util.Map;

/**
 * ObjectWrapper接口的抽象类，封装了MetaObject对象
 * 为子类 BeanWrapper 和 MapWrapper 提供集合属性值的获取和设置的公用方法。
 * @author Clinton Begin
 */
public abstract class BaseWrapper implements ObjectWrapper {

  protected static final Object[] NO_ARGUMENTS = new Object[0];
  protected final MetaObject metaObject;

  /**
   * 初始化metaObject。
   * 通过metaObject可以和获取对象的集合属性地址值，其他对象属性值由metaclass和object基于反射获取。
   * @param metaObject
   */
  protected BaseWrapper(MetaObject metaObject) {
    this.metaObject = metaObject;
  }

  /**
   * 解析属性表达式获取(指定对象的)(指定集合类型的属性下的)值
   * @param prop 指定属性解析器
   * @param object 指定对象
   * @return 集合属性值
   */
  protected Object resolveCollection(PropertyTokenizer prop, Object object) {
    if ("".equals(prop.getName())) {
      return object;
    } else {
      return metaObject.getValue(prop.getName());
    }
  }

  /**
   * 根据属性索引信息获取(指定集合的)(指定位置)的值
   * @param prop 指定属性解析器对象
   * @param collection 集合对象
   * @return
   */
  protected Object getCollectionValue(PropertyTokenizer prop, Object collection) {
    //如果是Map类型，则index为key
    if (collection instanceof Map) {
      return ((Map) collection).get(prop.getIndex());
    } else {
      //如果是其他集合类型，则index为下标
      int i = Integer.parseInt(prop.getIndex());
      if (collection instanceof List) {
        return ((List) collection).get(i);
      } else if (collection instanceof Object[]) {
        return ((Object[]) collection)[i];
      } else if (collection instanceof char[]) {
        return ((char[]) collection)[i];
      } else if (collection instanceof boolean[]) {
        return ((boolean[]) collection)[i];
      } else if (collection instanceof byte[]) {
        return ((byte[]) collection)[i];
      } else if (collection instanceof double[]) {
        return ((double[]) collection)[i];
      } else if (collection instanceof float[]) {
        return ((float[]) collection)[i];
      } else if (collection instanceof int[]) {
        return ((int[]) collection)[i];
      } else if (collection instanceof long[]) {
        return ((long[]) collection)[i];
      } else if (collection instanceof short[]) {
        return ((short[]) collection)[i];
      } else {
        throw new ReflectionException("The '" + prop.getName() + "' property of " + collection + " is not a List or Array.");
      }
    }
  }

  /**
   * 根据属性索引信息设置（指定集合的）（指定位置）（指定的）值
   * @param prop 属性解析器
   * @param collection 对象集合
   * @param value 值
   */
  protected void setCollectionValue(PropertyTokenizer prop, Object collection, Object value) {
    if (collection instanceof Map) {
      ((Map) collection).put(prop.getIndex(), value);
    } else {
      int i = Integer.parseInt(prop.getIndex());
      if (collection instanceof List) {
        ((List) collection).set(i, value);
      } else if (collection instanceof Object[]) {
        ((Object[]) collection)[i] = value;
      } else if (collection instanceof char[]) {
        ((char[]) collection)[i] = (Character) value;
      } else if (collection instanceof boolean[]) {
        ((boolean[]) collection)[i] = (Boolean) value;
      } else if (collection instanceof byte[]) {
        ((byte[]) collection)[i] = (Byte) value;
      } else if (collection instanceof double[]) {
        ((double[]) collection)[i] = (Double) value;
      } else if (collection instanceof float[]) {
        ((float[]) collection)[i] = (Float) value;
      } else if (collection instanceof int[]) {
        ((int[]) collection)[i] = (Integer) value;
      } else if (collection instanceof long[]) {
        ((long[]) collection)[i] = (Long) value;
      } else if (collection instanceof short[]) {
        ((short[]) collection)[i] = (Short) value;
      } else {
        throw new ReflectionException("The '" + prop.getName() + "' property of " + collection + " is not a List or Array.");
      }
    }
  }

}