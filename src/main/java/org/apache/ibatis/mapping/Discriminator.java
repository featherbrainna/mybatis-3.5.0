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
package org.apache.ibatis.mapping;

import org.apache.ibatis.session.Configuration;

import java.util.Collections;
import java.util.Map;

/**
 * 鉴别器
 * @author Clinton Begin
 */
public class Discriminator {

  /**
   * ResultMapping 对象
   */
  private ResultMapping resultMapping;
  /**
   * 映射
   * key:case节点的value属性
   * value:case节点的resultMap属性
   */
  private Map<String, String> discriminatorMap;

  Discriminator() {
  }

  /**
   * discriminator 对象的构建器
   */
  public static class Builder {
    /**
     * discriminator对象
     */
    private Discriminator discriminator = new Discriminator();

    /**
     * 初始化discriminator对象的 resultMapping、discriminatorMap 属性
     * @param configuration
     * @param resultMapping
     * @param discriminatorMap
     */
    public Builder(Configuration configuration, ResultMapping resultMapping, Map<String, String> discriminatorMap) {
      discriminator.resultMapping = resultMapping;
      discriminator.discriminatorMap = discriminatorMap;
    }

    /**
     * 检查并构建
     * @return Discriminator对象
     */
    public Discriminator build() {
      assert discriminator.resultMapping != null;
      assert discriminator.discriminatorMap != null;
      assert !discriminator.discriminatorMap.isEmpty();
      //lock down map
      discriminator.discriminatorMap = Collections.unmodifiableMap(discriminator.discriminatorMap);
      return discriminator;
    }
  }

  public ResultMapping getResultMapping() {
    return resultMapping;
  }

  public Map<String, String> getDiscriminatorMap() {
    return discriminatorMap;
  }

  public String getMapIdFor(String s) {
    return discriminatorMap.get(s);
  }

}
