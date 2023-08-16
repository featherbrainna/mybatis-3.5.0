/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 引用泛型抽象类。目的很简单，就是解析出类上定义的泛型参数。
 * 例如继承 BaseTypeHandler<Byte> 就解析出 Byte 类型
 * BaseTypeHandler的父类
 * References a generic type.
 *
 * @param <T> the referenced type
 * @since 3.1.0
 * @author Simone Tripodi
 */
public abstract class TypeReference<T> {

  /**
   * 泛型
   */
  private final Type rawType;

  /**
   * 构造器
   * 调用getSuperclassTypeParameter来初始化rawType
   */
  protected TypeReference() {
    rawType = getSuperclassTypeParameter(getClass());
  }

  /**
   * 获取<T>中T的类型
   * 所以 TypeHandler 的对应java类型不允许为泛型，只能是泛型参数的普通Class类型
   * @param clazz
   * @return
   */
  Type getSuperclassTypeParameter(Class<?> clazz) {
    // 0.获取本类的父类类型
    Type genericSuperclass = clazz.getGenericSuperclass();
    // 1.从父类中获取 <T>
    //1.1如果父类不是泛型类型而是普通类型，所以可能是其父类继承了TypeReference<T>
    if (genericSuperclass instanceof Class) {
      // try to climb up the hierarchy until meet something useful
      if (TypeReference.class != genericSuperclass) {
        //递归的寻找泛型参数<T>
        return getSuperclassTypeParameter(clazz.getSuperclass());
      }

      throw new TypeException("'" + getClass() + "' extends TypeReference but misses the type parameter. "
        + "Remove the extension or add a type parameter to it.");
    }

    // 2.从本类获取 <T>。如果父类不是普通类型，而是泛型类型
    //2.1获取泛型参数类型
    Type rawType = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
    // TODO remove this when Reflector is fixed to return Types
    //2.2如果泛型的参数又是泛型，才继续获取 <T>
    if (rawType instanceof ParameterizedType) {
      rawType = ((ParameterizedType) rawType).getRawType();
    }

    return rawType;
  }

  /**
   * 返回泛型参数
   * @return
   */
  public final Type getRawType() {
    return rawType;
  }

  @Override
  public String toString() {
    return rawType.toString();
  }

}
