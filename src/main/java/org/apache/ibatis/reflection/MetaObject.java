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

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 对象元数据，提供了对象的属性值的获得和设置等等方法。对 BaseWrapper 操作的进一步增强。
 * @author Clinton Begin
 */
public class MetaObject {

  /**
   * 原始JavaBean对象
   */
  private final Object originalObject;
  /**
   * ObjectWrapper对象，封装了originalObject对象
   */
  private final ObjectWrapper objectWrapper;
  /**
   * 负责实例化originalObject的工厂对象
   */
  private final ObjectFactory objectFactory;
  /**
   * 负责创建ObjectWrapper的工厂对象
   */
  private final ObjectWrapperFactory objectWrapperFactory;
  /**
   * 用于创建并缓存Reflector对象的工厂对象
   */
  private final ReflectorFactory reflectorFactory;

  /**
   * 构造器私有
   * @param object 目标对象
   * @param objectFactory 对象工厂对象
   * @param objectWrapperFactory 对象包装器工厂对象
   * @param reflectorFactory 反射工厂对象
   */
  private MetaObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    //初始化字段
    this.originalObject = object;
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;

    //0.根据object类型的不同创建不同的ObjectWrapper对象
    //1.若原始对象已经是 ObjectWrapper 对象，则直接使用
    if (object instanceof ObjectWrapper) {
      this.objectWrapper = (ObjectWrapper) object;
    } else if (objectWrapperFactory.hasWrapperFor(object)) {
      //2.默认始终为false，不可能通过objectWrapperFactory获取objectWrapper
      this.objectWrapper = objectWrapperFactory.getWrapperFor(this, object);
    } else if (object instanceof Map) {
      //3.若原始对象是Map类型，则创建MapWrapper对象
      this.objectWrapper = new MapWrapper(this, (Map) object);
    } else if (object instanceof Collection) {
      //4.若原始对象是Collection类型，则创建CollectionWrapper对象
      this.objectWrapper = new CollectionWrapper(this, (Collection) object);
    } else {
      //5.若原始对象是普通的JavaBean对象，则创建BeanWrapper对象
      this.objectWrapper = new BeanWrapper(this, object);
    }
  }

  /**
   * 只能通过此静态方法创建MetaObject对象，类似于MetaClass的编程模式
   * @param object 目标对象
   * @param objectFactory 对象工厂对象
   * @param objectWrapperFactory 对象包装器工厂对象
   * @param reflectorFactory 反射工厂对象
   * @return
   */
  public static MetaObject forObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    //如果目标对象为null则返回SystemMetaObject.NULL_META_OBJECT
    if (object == null) {
      return SystemMetaObject.NULL_META_OBJECT;
    } else {
      return new MetaObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
    }
  }

  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  public ObjectWrapperFactory getObjectWrapperFactory() {
    return objectWrapperFactory;
  }

  public ReflectorFactory getReflectorFactory() {
	return reflectorFactory;
  }

  public Object getOriginalObject() {
    return originalObject;
  }

  //######################################基于MetaClass实现############################################

  public String findProperty(String propName, boolean useCamelCaseMapping) {
    return objectWrapper.findProperty(propName, useCamelCaseMapping);
  }

  public String[] getGetterNames() {
    return objectWrapper.getGetterNames();
  }

  public String[] getSetterNames() {
    return objectWrapper.getSetterNames();
  }

  public Class<?> getSetterType(String name) {
    return objectWrapper.getSetterType(name);
  }

  public Class<?> getGetterType(String name) {
    return objectWrapper.getGetterType(name);
  }

  public boolean hasSetter(String name) {
    return objectWrapper.hasSetter(name);
  }

  public boolean hasGetter(String name) {
    return objectWrapper.hasGetter(name);
  }

  //##################################基于objectWrapper实现#######################################

  /**
   * 基于objectWrapper获取对应属性表达式的值
   * @param name 属性表达式
   * @return 属性表达式的值
   */
  public Object getValue(String name) {
    //1.解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.处理子表达式
    if (prop.hasNext()) {
      //2.1根据PropertyTokenizer解析后的属性，创建相应的MetaObject对象
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      //2.2若当前表达式的值为null，返回null
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return null;
      } else {
        //2.3表达式值不为null，递归处理子表达式
        return metaValue.getValue(prop.getChildren());
      }
    } else {
      //3.没有子表达式，通过objectWrapper获取指定的属性值
      return objectWrapper.get(prop);
    }
  }

  /**
   * 基于objectWrapper设置对应属性表达式的值
   * 但是不能初始 集合类型字段 中的 集合元素，路径中是集合元素为null时会报错，无法初始化设置集合元素！
   * @param name 属性表达式
   * @param value 值
   */
  public void setValue(String name, Object value) {
    //1.解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //2.处理子表达式
    if (prop.hasNext()) {
      //2.1根据PropertyTokenizer解析后的属性，创建相应的MetaObject对象
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      //2.2若当前表达式的值为null，根据情况初始化表达式路径上为空的属性
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        if (value == null) {
          // don't instantiate child path if value is null
          return;
        } else {
          //value不为空，初始化表达式路径上为空的属性
          metaValue = objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
        }
      }
      //2.3递归为属性表达式赋值
      metaValue.setValue(prop.getChildren(), value);
    } else {
      //3.没有子表达式，通过objectWrapper设置指定的属性值
      objectWrapper.set(prop, value);
    }
  }

  /**
   * 根据属性表达式创建MetaObject对象
   * @param name 属性表达式
   * @return MetaObject对象
   */
  public MetaObject metaObjectForProperty(String name) {
    //获取指定的属性值
    Object value = getValue(name);
    //创建该属性对象相应的MetaObject对象
    return MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
  }

  public ObjectWrapper getObjectWrapper() {
    return objectWrapper;
  }

  public boolean isCollection() {
    return objectWrapper.isCollection();
  }

  public void add(Object element) {
    objectWrapper.add(element);
  }

  public <E> void addAll(List<E> list) {
    objectWrapper.addAll(list);
  }

}
