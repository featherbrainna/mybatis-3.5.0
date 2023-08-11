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
package org.apache.ibatis.datasource.unpooled;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * UnpooledDataSourceFactory数据源工厂对象具体实现类
 * 一个工厂对象缓存对应唯一一个数据源
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

  private static final String DRIVER_PROPERTY_PREFIX = "driver.";
  private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

  protected DataSource dataSource;

  /**
   * UnpooledDataSourceFactory构造器。初始化dataSource为UnpooledDataSource对象
   */
  public UnpooledDataSourceFactory() {
    this.dataSource = new UnpooledDataSource();
  }

  /**
   * 设置数据源属性
   * @param properties 数据源属性
   */
  @Override
  public void setProperties(Properties properties) {
    Properties driverProperties = new Properties();
    //1.创建dataSource相应的MetaObject对象
    MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
    //2.遍历properties集合，该集合中配置了数据源需要的信息
    for (Object key : properties.keySet()) {
      //2.1获取属性名
      String propertyName = (String) key;
      if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
        //若属性名以”driver.“开头
        //2.2获取属性值
        String value = properties.getProperty(propertyName);
        //2.3设置属性到driverProperties
        driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
      } else if (metaDataSource.hasSetter(propertyName)) {
        //若属性名在数据源对象有setter方法可以设置
        //2.2获取属性值
        String value = (String) properties.get(propertyName);
        //2.3改变数据源的属性值
        Object convertedValue = convertValue(metaDataSource, propertyName, value);
        metaDataSource.setValue(propertyName, convertedValue);
      } else {
        //若不是以上情况抛出异常
        //2.2无法识别的数据源属性，抛出异常
        throw new DataSourceException("Unknown DataSource property: " + propertyName);
      }
    }
    //3.设置数据源的驱动属性
    if (driverProperties.size() > 0) {
      metaDataSource.setValue("driverProperties", driverProperties);
    }
  }

  @Override
  public DataSource getDataSource() {
    return dataSource;
  }

  /**
   * 属性值类型转换
   * @param metaDataSource 数据源对象对应的MetaObject对象
   * @param propertyName 属性名
   * @param value 原始String类型的属性值
   * @return 转换后属性值
   */
  private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
    Object convertedValue = value;
    //1.获取属性的真实类型
    Class<?> targetType = metaDataSource.getSetterType(propertyName);
    if (targetType == Integer.class || targetType == int.class) {
      convertedValue = Integer.valueOf(value);
    } else if (targetType == Long.class || targetType == long.class) {
      convertedValue = Long.valueOf(value);
    } else if (targetType == Boolean.class || targetType == boolean.class) {
      convertedValue = Boolean.valueOf(value);
    }
    //2.返回属性的真实类型
    return convertedValue;
  }

}
