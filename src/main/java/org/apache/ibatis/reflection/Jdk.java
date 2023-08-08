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

import org.apache.ibatis.io.Resources;

/**
 * 检查类是否加载成功
 * To check the existence of version dependent classes.
 */
public class Jdk {

  /**
   * 标记是否成功加载类java.lang.reflect.Parameter
   * <code>true</code> if <code>java.lang.reflect.Parameter</code> is available.
   * @deprecated Since 3.5.0, Will remove this field at feature(next major version up)
   */
  @Deprecated
  public static final boolean parameterExists;

  static {
    boolean available = false;
    try {
      //加载此类时先手动加载类java.lang.reflect.Parameter
      Resources.classForName("java.lang.reflect.Parameter");
      available = true;
    } catch (ClassNotFoundException e) {
      // ignore
    }
    parameterExists = available;
  }

  /**
   * 标记是否成功加载类dateAndTimeApiExists
   * @deprecated Since 3.5.0, Will remove this field at feature(next major version up)
   */
  @Deprecated
  public static final boolean dateAndTimeApiExists;

  static {
    boolean available = false;
    try {
      //加载此类时先手动加载类java.time.Clock
      Resources.classForName("java.time.Clock");
      available = true;
    } catch (ClassNotFoundException e) {
      // ignore
    }
    dateAndTimeApiExists = available;
  }

  /**
   * 标记是否成功加载类java.util.Optional
   * @deprecated Since 3.5.0, Will remove this field at feature(next major version up)
   */
  @Deprecated
  public static final boolean optionalExists;

  static {
    boolean available = false;
    try {
      //加载此类时先手动加载类java.util.Optional
      Resources.classForName("java.util.Optional");
      available = true;
    } catch (ClassNotFoundException e) {
      // ignore
    }
    optionalExists = available;
  }

  /**
   * 私有化构造器
   */
  private Jdk() {
    super();
  }
}
