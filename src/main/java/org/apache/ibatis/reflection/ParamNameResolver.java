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

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 参数名解析器
 */
public class ParamNameResolver {

  private static final String GENERIC_NAME_PREFIX = "param";

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   *
   * 参数名映射
   * KEY:参数顺序
   * VALUE:参数名
   */
  private final SortedMap<Integer, String> names;
  /**
   * 是否有{@link Param}注解的参数
   */
  private boolean hasParamAnnotation;

  /**
   * ParamNameResolver构造器
   * Param注解参数名优先于默认参数名，默认参数名优先于参数索引(从0开始)。三者选其一进行names集合的填充
   * 【注意】：默认参数名需要设置mybatis的全局设置
   * @param config mybatis配置对象
   * @param method 方法反射对象
   */
  public ParamNameResolver(Configuration config, Method method) {
    //获取参数类型数组
    final Class<?>[] paramTypes = method.getParameterTypes();
    //获取各个参数注解二维数组
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<>();
    //参数个数
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    //1.遍历各个参数进行处理
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      //2.如果是特殊类型的参数，忽略。（RowBounds、ResultHandler）
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
        continue;
      }
      //3.从@Param注解获取参数
      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        if (annotation instanceof Param) {
          hasParamAnnotation = true;
          name = ((Param) annotation).value();
          break;
        }
      }
      //4.没有从注解获取参数名时
      if (name == null) {
        // @Param was not specified.
        if (config.isUseActualParamName()) {//默认开启
          //4.1.获取指定方法指定索引下的参数名
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          //4.2.使用索引作为参数名
          name = String.valueOf(map.size());
        }
      }
      //5.放入map参数索引、参数名键值对，继续处理后续参数
      map.put(paramIndex, name);
    }
    //6.使用map初始化对象字段names，构建不可变集合
    names = Collections.unmodifiableSortedMap(map);
  }

  /**
   * 委托ParamNameUtil获取指定方法指定索引的参数名
   * @param method 指定方法
   * @param paramIndex 指定索引
   * @return 参数名
   */
  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * 获得参数名与值的映射。除构造器外唯一公共方法
   * 映射组合 1 ：KEY：参数名，VALUE：参数值
   * 映射组合 2 ：KEY：GENERIC_NAME_PREFIX + 参数顺序，VALUE ：参数值
   * @param args 参数值数组
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    //参数个数
    final int paramCount = names.size();
    //1.args参数为null或者方法参数个数为0，直接返回null
    if (args == null || paramCount == 0) {
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) {
      //2.只有一个非注解的参数，直接返回首元素值
      return args[names.firstKey()];
    } else {
      //3.声明参数名与参数值映射集合，两种组合都必定同时存在，除非参数名与"param1"冲突
      // 组合 1 ：KEY：参数名，VALUE：参数值
      // 组合 2 ：KEY：GENERIC_NAME_PREFIX + 参数顺序，VALUE ：参数值
      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      //3.1遍历 names 集合
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        //3.2组合 1 ：添加到 param 中
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        //3.3组合 2 ：添加到 param 中
        final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
        // ensure not to overwrite parameter named with @Param
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      //4.返回参数名与参数值映射集合
      return param;
    }
  }
}
