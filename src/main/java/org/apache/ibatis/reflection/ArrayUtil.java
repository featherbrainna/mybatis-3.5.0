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
package org.apache.ibatis.reflection;

import java.util.Arrays;

/**
 * 数组工具类。主要为不同类型数组对象提供了通用的hashCode, equals and toString方法。
 * Provides hashCode, equals and toString methods that can handle array.
 */
public class ArrayUtil {

  /**
   * 返回对象的hashcode
   * Returns a hash code for {@code obj}.
   *
   * @param obj
   *          The object to get a hash code for. May be an array or <code>null</code>.
   * @return A hash code of {@code obj} or 0 if {@code obj} is <code>null</code>
   */
  public static int hashCode(Object obj) {
    //1.对象为null返回0
    if (obj == null) {
      // for consistency with Arrays#hashCode() and Objects#hashCode()
      return 0;
    }
    //2.对象为普通对象类型，使用对象的方法生成hashcode
    final Class<?> clazz = obj.getClass();
    if (!clazz.isArray()) {
      return obj.hashCode();
    }
    //3.对象为数组类型
    //获取元素类型
    final Class<?> componentType = clazz.getComponentType();
    //根据元素类型来调用Arrays.hashCode计算hash值
    if (long.class.equals(componentType)) {
      return Arrays.hashCode((long[]) obj);
    } else if (int.class.equals(componentType)) {
      return Arrays.hashCode((int[]) obj);
    } else if (short.class.equals(componentType)) {
      return Arrays.hashCode((short[]) obj);
    } else if (char.class.equals(componentType)) {
      return Arrays.hashCode((char[]) obj);
    } else if (byte.class.equals(componentType)) {
      return Arrays.hashCode((byte[]) obj);
    } else if (boolean.class.equals(componentType)) {
      return Arrays.hashCode((boolean[]) obj);
    } else if (float.class.equals(componentType)) {
      return Arrays.hashCode((float[]) obj);
    } else if (double.class.equals(componentType)) {
      return Arrays.hashCode((double[]) obj);
    } else {
      return Arrays.hashCode((Object[]) obj);
    }
  }

  /**
   * 判断thisObj与thatObj是否相等
   * Compares two objects. Returns <code>true</code> if
   * <ul>
   * <li>{@code thisObj} and {@code thatObj} are both <code>null</code></li>
   * <li>{@code thisObj} and {@code thatObj} are instances of the same type and
   * {@link Object#equals(Object)} returns <code>true</code></li>
   * <li>{@code thisObj} and {@code thatObj} are arrays with the same component type and
   * equals() method of {@link Arrays} returns <code>true</code> (not deepEquals())</li>
   * </ul>
   *
   * @param thisObj
   *          The left hand object to compare. May be an array or <code>null</code>
   * @param thatObj
   *          The right hand object to compare. May be an array or <code>null</code>
   * @return <code>true</code> if two objects are equal; <code>false</code> otherwise.
   */
  public static boolean equals(Object thisObj, Object thatObj) {
    //1.thisObj为null时，返回取决于thatObj是否为null
    if (thisObj == null) {
      return thatObj == null;
    } else if (thatObj == null) {
      //2.thatObj为null时，返回false
      return false;
    }
    //3.先比较两者类型，类型不同返回false
    final Class<?> clazz = thisObj.getClass();
    if (!clazz.equals(thatObj.getClass())) {
      return false;
    }
    //3.比较同类型普通对象是否相等
    if (!clazz.isArray()) {
      return thisObj.equals(thatObj);
    }
    //4.比较同类型数组对象是否相等
    final Class<?> componentType = clazz.getComponentType();
    if (long.class.equals(componentType)) {
      return Arrays.equals((long[]) thisObj, (long[]) thatObj);
    } else if (int.class.equals(componentType)) {
      return Arrays.equals((int[]) thisObj, (int[]) thatObj);
    } else if (short.class.equals(componentType)) {
      return Arrays.equals((short[]) thisObj, (short[]) thatObj);
    } else if (char.class.equals(componentType)) {
      return Arrays.equals((char[]) thisObj, (char[]) thatObj);
    } else if (byte.class.equals(componentType)) {
      return Arrays.equals((byte[]) thisObj, (byte[]) thatObj);
    } else if (boolean.class.equals(componentType)) {
      return Arrays.equals((boolean[]) thisObj, (boolean[]) thatObj);
    } else if (float.class.equals(componentType)) {
      return Arrays.equals((float[]) thisObj, (float[]) thatObj);
    } else if (double.class.equals(componentType)) {
      return Arrays.equals((double[]) thisObj, (double[]) thatObj);
    } else {
      return Arrays.equals((Object[]) thisObj, (Object[]) thatObj);
    }
  }

  /**
   * obj的toString方法
   * If the {@code obj} is an array, toString() method of {@link Arrays} is called. Otherwise
   * {@link Object#toString()} is called. Returns "null" if {@code obj} is <code>null</code>.
   *
   * @param obj
   *          An object. May be an array or <code>null</code>.
   * @return String representation of the {@code obj}.
   */
  public static String toString(Object obj) {
    //1.obj为null时返回null字符串
    if (obj == null) {
      return "null";
    }
    //2.普通对象时，使用普通对象的toString方法
    final Class<?> clazz = obj.getClass();
    if (!clazz.isArray()) {
      return obj.toString();
    }
    //3.数组对象时，依据元素类型使用数组的Arrays.toString方法
    final Class<?> componentType = obj.getClass().getComponentType();
    if (long.class.equals(componentType)) {
      return Arrays.toString((long[]) obj);
    } else if (int.class.equals(componentType)) {
      return Arrays.toString((int[]) obj);
    } else if (short.class.equals(componentType)) {
      return Arrays.toString((short[]) obj);
    } else if (char.class.equals(componentType)) {
      return Arrays.toString((char[]) obj);
    } else if (byte.class.equals(componentType)) {
      return Arrays.toString((byte[]) obj);
    } else if (boolean.class.equals(componentType)) {
      return Arrays.toString((boolean[]) obj);
    } else if (float.class.equals(componentType)) {
      return Arrays.toString((float[]) obj);
    } else if (double.class.equals(componentType)) {
      return Arrays.toString((double[]) obj);
    } else {
      return Arrays.toString((Object[]) obj);
    }
  }

}
