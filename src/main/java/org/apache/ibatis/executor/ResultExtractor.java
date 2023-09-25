/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.executor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Array;
import java.util.List;

/**
 * 结果提取器
 * @author Andrew Gustafson
 */
public class ResultExtractor {
  /**
   * 配置对象
   */
  private final Configuration configuration;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;

  /**
   * 构造器，初始化属性
   * @param configuration 配置对象
   * @param objectFactory 对象工厂
   */
  public ResultExtractor(Configuration configuration, ObjectFactory objectFactory) {
    this.configuration = configuration;
    this.objectFactory = objectFactory;
  }

  /**
   * 将 结果对象list 提取为 目标类型的对象
   * 1.如果目标类型为list，则无需转换
   * 2.如果目标类型为集合类型，则创建集合对象并复制元素到集合对象
   * 3.如果目标类型为数组类型，则创建数组对象并复制元素到数组对象
   * 4.如果目标类型为其它类型，则提取唯一元素返回
   * @param list 结果对象
   * @param targetType 目标类型
   * @return 提取后结果对象
   */
  public Object extractObjectFromList(List<Object> list, Class<?> targetType) {
    Object value = null;
    //1.如果目标类型为list，则无需转换
    if (targetType != null && targetType.isAssignableFrom(list.getClass())) {
      value = list;
    } else if (targetType != null && objectFactory.isCollection(targetType)) {
      //2.如果目标类型为集合类型，则创建集合对象并复制元素到集合对象
      //2.1通过objectFactory创建目标类型的对象
      value = objectFactory.create(targetType);
      //2.2通过configuration获取目标对象的MetaObject
      MetaObject metaObject = configuration.newMetaObject(value);
      //2.3将list添加到metaObject
      metaObject.addAll(list);
    } else if (targetType != null && targetType.isArray()) {
      //3.如果目标类型为数组类型，则创建数组对象并复制元素到数组对象
      //3.1创建数组对象array
      Class<?> arrayComponentType = targetType.getComponentType();
      Object array = Array.newInstance(arrayComponentType, list.size());
      //3.2用list初始化数组值。分为 java基本数据类型 和 其它类型 处理
      if (arrayComponentType.isPrimitive()) {
        for (int i = 0; i < list.size(); i++) {
          Array.set(array, i, list.get(i));
        }
        value = array;
      } else {
        value = list.toArray((Object[])array);
      }
    } else {
      //4.如果目标类型为其它类型，则提取唯一元素返回
      //4.1如果list元素大于1，则抛出异常
      if (list != null && list.size() > 1) {
        throw new ExecutorException("Statement returned more than one row, where no more than one was expected.");
      } else if (list != null && list.size() == 1) {
        //4.2获取唯一的元素对象
        value = list.get(0);
      }
    }
    return value;
  }
}
