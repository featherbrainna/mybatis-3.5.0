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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * 参数名工具类，获得构造方法、普通方法的参数列表
 */
public class ParamNameUtil {
  /**
   * 获得普通方法的参数名列表
   * @param method 普通方法
   * @return 参数名列表
   */
  public static List<String> getParamNames(Method method) {
    return getParameterNames(method);
  }

  /**
   * 获得构造器方法的参数名列表
   * @param constructor 构造器方法
   * @return 参数名列表
   */
  public static List<String> getParamNames(Constructor<?> constructor) {
    return getParameterNames(constructor);
  }

  /**
   * 底层获取参数名列表的方法。使用反射获取
   * @param executable
   * @return
   */
  private static List<String> getParameterNames(Executable executable) {
    final List<String> names = new ArrayList<>();
    // 获得 Parameter 数组
    final Parameter[] params = executable.getParameters();
    for (Parameter param : params) {
      // 获得参数名，并添加到 names 中
      names.add(param.getName());
    }
    return names;
  }

  /**
   * 构造器私有化，静态工具类
   */
  private ParamNameUtil() {
    super();
  }
}
