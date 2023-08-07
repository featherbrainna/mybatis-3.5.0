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

import org.apache.ibatis.reflection.*;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * 普通对象的ObjectWrapper实现类
 * @author Clinton Begin
 */
public class BeanWrapper extends BaseWrapper {

  /**
   * 封装的一个对象
   */
  private final Object object;
  /**
   * 封装的对象的类相应的MetaClass对象
   */
  private final MetaClass metaClass;

  /**
   * 本类object和metaClass初始化，父类metaObject初始化
   * @param metaObject
   * @param object
   */
  public BeanWrapper(MetaObject metaObject, Object object) {
    super(metaObject);
    this.object = object;
    // 创建 MetaClass 对象
    this.metaClass = MetaClass.forClass(object.getClass(), metaObject.getReflectorFactory());
  }

  /**
   * 获取指定属性的值（当前属性不递归）
   * @param prop 属性解析器对象
   * @return 属性值
   */
  @Override
  public Object get(PropertyTokenizer prop) {
    //1.存在索引信息，则属性表达式中的name部分为集合类型
    if (prop.getIndex() != null) {
      //1.1获取Object对象中的指定集合属性值
      Object collection = resolveCollection(prop, object);
      //1.2获取集合元素
      return getCollectionValue(prop, collection);
    } else {
      //2.不存在索引信息，则name部分为普通对象，查找并调用Invoker相关方法获取属性
      return getBeanProperty(prop, object);
    }
  }

  /**
   * 设置属性值（当前属性不递归）
   * @param prop 属性解析器对象
   * @param value 设置的值
   */
  @Override
  public void set(PropertyTokenizer prop, Object value) {
    //1.存在索引信息，则属性表达式中的name部分为集合类型
    if (prop.getIndex() != null) {
      //1.1获取Object对象中的指定集合属性值
      Object collection = resolveCollection(prop, object);
      //1.2设置集合属性元素
      setCollectionValue(prop, collection, value);
    } else {
      //2.不存在索引信息，则name部分为普通对象，查找并调用Invoker相关方法设置属性
      setBeanProperty(prop, object, value);
    }
  }

  @Override
  public String findProperty(String name, boolean useCamelCaseMapping) {
    return metaClass.findProperty(name, useCamelCaseMapping);
  }

  @Override
  public String[] getGetterNames() {
    return metaClass.getGetterNames();
  }

  @Override
  public String[] getSetterNames() {
    return metaClass.getSetterNames();
  }

  /**
   * 获得指定属性的setter方法的参数值类型
   * @param name 属性表达式
   * @return 参数值类型
   */
  @Override
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return metaClass.getSetterType(name);
      } else {
        return metaValue.getSetterType(prop.getChildren());
      }
    } else {
      return metaClass.getSetterType(name);
    }
  }

  /**
   * 获得指定属性的getter方法的返回值类型
   * @param name 属性表达式
   * @return 返回值类型
   */
  @Override
  public Class<?> getGetterType(String name) {
    //1.解析属性表达式，对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.有子属性表达式
    if (prop.hasNext()) {
      //创建MetaObject对象
      MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
      // 如果 metaValue 为空，则基于 metaClass 获得返回类型
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return metaClass.getGetterType(name);
        // 如果 metaValue 非空，则基于 metaValue 获得返回类型。
      } else {
        return metaValue.getGetterType(prop.getChildren());
      }
    } else {
      //3.没有子属性表达式，直接使用metaClass返回
      return metaClass.getGetterType(name);
    }
  }

  @Override
  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (metaClass.hasSetter(prop.getIndexedName())) {
        MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
        if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
          return metaClass.hasSetter(name);
        } else {
          return metaValue.hasSetter(prop.getChildren());
        }
      } else {
        return false;
      }
    } else {
      return metaClass.hasSetter(name);
    }
  }

  /**
   * 是否有指定属性的getter方法
   * @param name 属性表达式
   * @return
   */
  @Override
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (metaClass.hasGetter(prop.getIndexedName())) {
        MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
        if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
          return metaClass.hasGetter(name);
        } else {
          return metaValue.hasGetter(prop.getChildren());
        }
      } else {
        return false;
      }
    } else {
      return metaClass.hasGetter(name);
    }
  }

  /**
   * 创建指定属性的值
   * @param name
   * @param prop
   * @param objectFactory
   * @return
   */
  @Override
  public MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory) {
    MetaObject metaValue;
    //获得当前属性setter方法的参数类型
    Class<?> type = getSetterType(prop.getName());
    try {
      //创建对象
      Object newObject = objectFactory.create(type);
      //创建MetaObject对象
      metaValue = MetaObject.forObject(newObject, metaObject.getObjectFactory(), metaObject.getObjectWrapperFactory(), metaObject.getReflectorFactory());
      //设置当前对象的值
      set(prop, newObject);
    } catch (Exception e) {
      throw new ReflectionException("Cannot set value of property '" + name + "' because '" + name + "' is null and cannot be instantiated on instance of " + type.getName() + ". Cause:" + e.toString(), e);
    }
    return metaValue;
  }

  /**
   * 获取对象中的属性值
   * @param prop 属性解析器对象
   * @param object 对象
   * @return 属性值
   */
  private Object getBeanProperty(PropertyTokenizer prop, Object object) {
    try {
      //1.根据属性名获取属性的getter方法
      Invoker method = metaClass.getGetInvoker(prop.getName());
      try {
        //2.调用该对象的getter方法获取属性值
        return method.invoke(object, NO_ARGUMENTS);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable t) {
      throw new ReflectionException("Could not get property '" + prop.getName() + "' from " + object.getClass() + ".  Cause: " + t.toString(), t);
    }
  }

  /**
   * 设置对象中的属性值
   * @param prop 属性解析器对象
   * @param object 对象
   * @param value 属性值
   */
  private void setBeanProperty(PropertyTokenizer prop, Object object, Object value) {
    try {
      //1.根据属性名获取属性的setter方法
      Invoker method = metaClass.getSetInvoker(prop.getName());
      //封装参数
      Object[] params = {value};
      try {
        //2.调用该对象的setter方法设置属性值
        method.invoke(object, params);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    } catch (Throwable t) {
      throw new ReflectionException("Could not set property '" + prop.getName() + "' of '" + object.getClass() + "' with value '" + value + "' Cause: " + t.toString(), t);
    }
  }

  @Override
  public boolean isCollection() {
    return false;
  }

  @Override
  public void add(Object element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <E> void addAll(List<E> list) {
    throw new UnsupportedOperationException();
  }

}
