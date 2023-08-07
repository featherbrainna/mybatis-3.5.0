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
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * 组合使用Reflector和PropertyTokenizer。进一步封装了Reflector、ReflectorFactory和PropertyTokenizer
 * @author Clinton Begin
 */
public class MetaClass {

  /**
   * reflectorFactory对象，用于缓存reflector
   */
  private final ReflectorFactory reflectorFactory;
  /**
   * 在创建MetaClass时指定类对应的reflector对象，记录该类相关的元信息
   */
  private final Reflector reflector;

  /**
   * 私有构造器，通过类对象和reflectorFactory工厂对象创建对象
   * @param type
   * @param reflectorFactory
   */
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    //通过工厂类来创建reflector对象
    this.reflector = reflectorFactory.findForClass(type);
  }

  /**
   * 使用静态方法创建指定类的MetaClass对象
   * @param type 类类型对象
   * @param reflectorFactory 工厂对象缓存reflector
   * @return MetaClass对象
   */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 创建类的指定属性的类的 MetaClass 对象。（无递归处理，不存在子属性）
   * @param name 属性名
   * @return
   */
  public MetaClass metaClassForProperty(String name) {
    //获取属性类类型
    Class<?> propType = reflector.getGetterType(name);
    //创建属性类对应的Metaclass对象
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 根据表达式获取本类属性名称构成的表达式。不包含属性索引下标"[]"
   * @param name 属性表达式（不区分大小写）
   * @return 真实的属性名表达式字符串
   */
  public String findProperty(String name) {
    //委托buildProperty（）方法实现
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  /**
   * 获取属性setter方法参数类型
   * @param name 属性表达式
   * @return 类类型
   */
  public Class<?> getSetterType(String name) {
    //1.解析属性表达式，对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子表达式
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      //3.无子表达式
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * 获取属性getter方法的返回值类类型
   * @param name 属性表达式
   * @return 类类型
   */
  public Class<?> getGetterType(String name) {
    //1.解析属性表达式，对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子表达式
    if (prop.hasNext()) {
      //调用metaClassForProperty(prop)方法
      MetaClass metaProp = metaClassForProperty(prop);
      //递归调用
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    //3.调用重载方法
    return getGetterType(prop);
  }

  /**
   * 创建该类指定表达式解析器对象的MetaClass对象
   * @param prop 表达式解析器对象
   * @return
   */
  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    //委托本类的getGetterType()方法实现获取属性的类类型
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取属性getter方法的类类型
   * 属性有索引，则返回泛型的参数类型；没有索引则返回reflector提供的类型
   * @param prop 表达式解析器对象
   * @return 类类型
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    //1.获取解析器当前属性名的类类型
    Class<?> type = reflector.getGetterType(prop.getName());
    //有索引，且是Collection子类。如果获取数组的某个位置的元素，则获取其泛型。例如说：list[0].field ，那么就会解析 list 是什么类型，这样才好通过该类型，继续获得 field
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      //2.获取泛型类型
      Type returnType = getGenericGetterType(prop.getName());
      //判断是泛型，则针对泛型集合类型进行处理
      if (returnType instanceof ParameterizedType) {
        //3.获取实际的类型参数（即泛型<>里面的类型）
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          //泛型类型
          returnType = actualTypeArguments[0];
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  /**
   * 由getGetterType调用，获取（无子属性）属性的声明的getter方法返回值类型（类型包括泛型和普通类型）
   * @param propertyName 单个属性名
   * @return
   */
  private Type getGenericGetterType(String propertyName) {
    //1.根据Reflector.getMethods集合记录的Invoker实现类型，决定解析getter方法返回值类型还是解析字段类型
    try {
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        //2.解析方法返回值类型
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        //3.解析字段类型
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  /**
   * 检测是否有表达式对应的setter方法
   * @param name 表达式（区分大小写）
   * @return
   */
  public boolean hasSetter(String name) {
    //1.解析器解析表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子表达式，递归处理
    if (prop.hasNext()) {
      //当前属性有setter方法才递归处理
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        //递归处理
        return metaProp.hasSetter(prop.getChildren());
      } else {
        //没有直接返回false
        return false;//递归出口
      }
    } else {
      //3.无子表达式，直接委托reflector判断处理
      return reflector.hasSetter(prop.getName());//递归出口
    }
  }

  /**
   * 检测是否有表达式对应的getter方法
   * @param name 表达式（区分大小写）
   * @return
   */
  public boolean hasGetter(String name) {
    //1.解析表达式，对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子属性
    if (prop.hasNext()) {
      //2.1当前属性有getter方法
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        //2.2递归处理子属性
        return metaProp.hasGetter(prop.getChildren());
      } else {
        //2.1当前属性没有getter方法
        return false;
      }
    } else {
      //3.没有子属性，判断是否有该属性的getter方法
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  /**
   * findProperty()方法的具体底层实现，依据属性表达式查找本类属性的真实名称
   * @param name 属性表达式（不区分大小写）
   * @param builder 属性名字符串构建器
   * @return 属性名字符串构建器
   */
  private StringBuilder buildProperty(String name, StringBuilder builder) {
    //1.使用属性表达式解析器解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子表达式
    if (prop.hasNext()) {
      //获取属性名，注：属性表达式大小写不区分！
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        //追加真实的属性名
        builder.append(propertyName);
        builder.append(".");
        //2.为该属性创建对应的MetaClass对象
        MetaClass metaProp = metaClassForProperty(propertyName);
        //3.递归解析PropertyTokenizer.children字段，并将解析结果添加到builder中保存
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {
      //4.递归出口，没有子属性表达式
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
