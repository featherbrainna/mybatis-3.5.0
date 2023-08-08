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
package org.apache.ibatis.reflection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * 异常工具类
 * @author Clinton Begin
 */
public class ExceptionUtil {

  /**
   * 私有化构造器，静态工具类
   */
  private ExceptionUtil() {
    // Prevent Instantiation
  }

  /**
   * 去掉异常的包装
   * @param wrapped 被包装的异常
   * @return 去除包装后的异常
   */
  public static Throwable unwrapThrowable(Throwable wrapped) {
    Throwable unwrapped = wrapped;
    //循环处理包装的异常，直至异常类型非InvocationTargetException和UndeclaredThrowableException
    while (true) {
      if (unwrapped instanceof InvocationTargetException) {
        //1.InvocationTargetException去包装
        unwrapped = ((InvocationTargetException) unwrapped).getTargetException();
      } else if (unwrapped instanceof UndeclaredThrowableException) {
        //2.UndeclaredThrowableException去包装
        unwrapped = ((UndeclaredThrowableException) unwrapped).getUndeclaredThrowable();
      } else {
        //3.其他类型无需去包装
        return unwrapped;
      }
    }
  }

}
