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
package org.apache.ibatis.builder;

import org.apache.ibatis.cache.Cache;

/**
 * Cache 指向解析器
 * @author Clinton Begin
 */
public class CacheRefResolver {
  private final MapperBuilderAssistant assistant;
  /**
   * Cache 指向的命名空间，即cache-ref节点得 namespace属性
   */
  private final String cacheRefNamespace;

  public CacheRefResolver(MapperBuilderAssistant assistant, String cacheRefNamespace) {
    this.assistant = assistant;
    this.cacheRefNamespace = cacheRefNamespace;
  }

  /**
   * 解析指向的 cache-ref,底层使用 {@link MapperBuilderAssistant#useCacheRef(String)} 实现解析
   * ，而更底层则通过 configuration 缓存的 caches自动实现解析对应namespace的缓存对象
   * @return
   */
  public Cache resolveCacheRef() {
    return assistant.useCacheRef(cacheRefNamespace);
  }
}