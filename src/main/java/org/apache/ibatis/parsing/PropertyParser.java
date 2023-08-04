/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.parsing;

import java.util.Properties;

/**
 * 属性解析器。处理xml的属性占位符。
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class PropertyParser {

  private static final String KEY_PREFIX = "org.apache.ibatis.parsing.PropertyParser.";
  /**
   * 在mybatis-config.xml中properties节点下配置是否开启默认值功能的对应配置项
   * The special property key that indicate whether enable a default value on placeholder.
   * <p>
   *   The default value is {@code false} (indicate disable a default value on placeholder)
   *   If you specify the {@code true}, you can specify key and default value on placeholder (e.g. {@code ${db.username:postgres}}).
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_ENABLE_DEFAULT_VALUE = KEY_PREFIX + "enable-default-value";

  /**
   * 配置占位符与默认值之间的默认分隔符的对应配置项
   * The special property key that specify a separator for key and default value on placeholder.
   * <p>
   *   The default separator is {@code ":"}.
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_DEFAULT_VALUE_SEPARATOR = KEY_PREFIX + "default-value-separator";

  //默认情况下，关闭默认值的功能
  private static final String ENABLE_DEFAULT_VALUE = "false";

  //默认分隔符是冒号
  private static final String DEFAULT_VALUE_SEPARATOR = ":";

  //禁止构造 PropertyParser 对象，因为它是一个静态方法的工具类
  private PropertyParser() {
    // Prevent Instantiation
  }

  public static String parse(String string, Properties variables) {
    //1.创建VariableTokenHandler对象
    VariableTokenHandler handler = new VariableTokenHandler(variables);
    //2.创建GenericTokenParser对象，并指定其处理的占位符格式为"${}"，解析占位符值
    GenericTokenParser parser = new GenericTokenParser("${", "}", handler);
    //3.委托GenericTokenParser执行解析
    return parser.parse(string);
  }

  /**
   * 底层属性占位符处理器。被GenericTokenParser封装，并由它调用；而GenericTokenParser又由PropertyParser方法调用
   */
  private static class VariableTokenHandler implements TokenHandler {
    //properties节点下定义的键值对，用于替换占位符
    private final Properties variables;
    //是否支持占位符中使用默认值的功能
    private final boolean enableDefaultValue;
    //指定占位符和默认值之间的分隔符
    private final String defaultValueSeparator;

    private VariableTokenHandler(Properties variables) {
      this.variables = variables;
      this.enableDefaultValue = Boolean.parseBoolean(getPropertyValue(KEY_ENABLE_DEFAULT_VALUE, ENABLE_DEFAULT_VALUE));
      this.defaultValueSeparator = getPropertyValue(KEY_DEFAULT_VALUE_SEPARATOR, DEFAULT_VALUE_SEPARATOR);
    }

    /**
     * 初始化enableDefaultValue和defaultValueSeparator的方法。
     * variables为null，直接返回传入的默认值；
     *          不为null查找variables对应的属性值，找不到返回传入的默认值，找到返回属性值。
     * @param key
     * @param defaultValue
     * @return
     */
    private String getPropertyValue(String key, String defaultValue) {
      return (variables == null) ? defaultValue : variables.getProperty(key, defaultValue);
    }

    @Override
    public String handleToken(String content) {
      //检测variables集合是否为空
      if (variables != null) {
        String key = content;
        //检测是否支持占位符中使用默认值的功能
        if (enableDefaultValue) {//设置支持默认值
          //查找分割符
          final int separatorIndex = content.indexOf(defaultValueSeparator);
          String defaultValue = null;
          if (separatorIndex >= 0) {
            //获取占位符的属性名称
            key = content.substring(0, separatorIndex);
            //获取占位符设置的默认值
            defaultValue = content.substring(separatorIndex + defaultValueSeparator.length());
          }
          if (defaultValue != null) {
            //支持默认值且设置了占位符默认值，则查找properties节点并返回
            return variables.getProperty(key, defaultValue);
          }
        }
        //不支持默认值的功能 或 支持但没有设置默认值，则直接查找variable集合
        if (variables.containsKey(key)) {
          return variables.getProperty(key);
        }
      }
      //variables集合为空，即没有定义属性节点
      return "${" + content + "}";
    }
  }

}
