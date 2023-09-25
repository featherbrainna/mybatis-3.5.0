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
package org.apache.ibatis.executor.result;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Map;

/**
 * Map类型结果处理器。实现了 ResultHandler 接口
 * @author Clinton Begin
 */
public class DefaultMapResultHandler<K, V> implements ResultHandler<V> {

  /**
   * Map结果对象
   */
  private final Map<K, V> mappedResults;
  /**
   * mappedResults的key的属性名
   */
  private final String mapKey;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;
  /**
   * 对象包装工厂
   */
  private final ObjectWrapperFactory objectWrapperFactory;
  /**
   * 反射工厂
   */
  private final ReflectorFactory reflectorFactory;

  /**
   * 构造器，初始化属性mapKey、objectFactory、objectWrapperFactory、reflectorFactory
   * 由 {@link org.apache.ibatis.session.defaults.DefaultSqlSession#selectMap(String, Object, String, RowBounds)} 调用
   */
  @SuppressWarnings("unchecked")
  public DefaultMapResultHandler(String mapKey, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;
    //1.使用HashMap对象初始化 mappedResults 属性
    this.mappedResults = objectFactory.create(Map.class);
    this.mapKey = mapKey;
  }

  /**
   * 处理结果，生成mappedResults的数据
   * @param context 结果上下文
   */
  @Override
  public void handleResult(ResultContext<? extends V> context) {
    //1.从结果上下文获取当前结果对象
    final V value = context.getResultObject();
    //2.创建当前结果对象的 MetaObject
    final MetaObject mo = MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
    // TODO is that assignment always true?
    //3.从MetaObject获取属性名为 mapKey 的属性值
    final K key = (K) mo.getValue(mapKey);
    //4.以属性值为key，当前结果对象为value将数据添加到mappedResults映射
    mappedResults.put(key, value);
  }

  /**
   * 获取Map结果对象
   * @return Map结果对象
   */
  public Map<K, V> getMappedResults() {
    return mappedResults;
  }
}
