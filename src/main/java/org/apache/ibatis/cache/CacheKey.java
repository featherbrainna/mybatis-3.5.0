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
package org.apache.ibatis.cache;

import org.apache.ibatis.reflection.ArrayUtil;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 缓存键，实现 Cloneable、Serializable 接口
 * @author Clinton Begin
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  /**
   * 单例 - 空缓存键
   */
  public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

  /**
   * 默认 {@link #multiplier} 的值
   */
  private static final int DEFAULT_MULTIPLYER = 37;
  /**
   * 默认 {@link #hashcode} 的值
   */
  private static final int DEFAULT_HASHCODE = 17;

  /**
   * 参与计算hashcode，默认值是37，hashcode 求值的系数
   */
  private final int multiplier;
  /**
   * CacheKey对象的hashcode，初始值是17
   */
  private int hashcode;
  /**
   * 校验和
   */
  private long checksum;
  /**
   * updateList集合元素的个数
   */
  private int count;
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
  /**
   * key集合，由该集合中的所有对象共同决定两个CacheKey是否相同
   */
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<>();
  }

  public CacheKey(Object[] objects) {
    this();
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  /**
   * 向updateList集合中添加对象
   * @param object
   */
  public void update(Object object) {
    //1.计算object的hashcode。为null时为1，其他委托ArrayUtil计算
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);

    //2.更新字段值
    count++;
    checksum += baseHashCode;
    baseHashCode *= count;

    //3.计算本CacheKey对象的hashcode，更新hashcode字段
    hashcode = multiplier * hashcode + baseHashCode;

    //4.将object添加到updateList集合
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }

  @Override
  public boolean equals(Object object) {
    //1.是否是同一对象
    if (this == object) {
      return true;
    }
    //2.是否类型相同，类型不同返回false
    if (!(object instanceof CacheKey)) {
      return false;
    }

    //3.类型相同，继续以下比较
    final CacheKey cacheKey = (CacheKey) object;

    //3.1比较hashcode字段
    if (hashcode != cacheKey.hashcode) {
      return false;
    }
    //3.2比较checksum字段
    if (checksum != cacheKey.checksum) {
      return false;
    }
    //3.3比较count字段
    if (count != cacheKey.count) {
      return false;
    }

    //3.4比较updateList集合元素
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 返回hashcode字段，在每次update时更新hashcode字段
   * @return
   */
  @Override
  public int hashCode() {
    return hashcode;
  }

  @Override
  public String toString() {
    StringBuilder returnValue = new StringBuilder().append(hashcode).append(':').append(checksum);
    for (Object object : updateList) {
      returnValue.append(':').append(ArrayUtil.toString(object));
    }
    return returnValue.toString();
  }

  /**
   * 深拷贝
   * @return
   * @throws CloneNotSupportedException
   */
  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<>(updateList);
    return clonedCacheKey;
  }

}
