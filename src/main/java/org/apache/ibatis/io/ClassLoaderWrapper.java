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
package org.apache.ibatis.io;

import java.io.InputStream;
import java.net.URL;

/**
 * ClassLoader 包装器。可使用多个 ClassLoader 加载对应的资源，直到有一成功后返回资源。
 * A class to wrap access to multiple class loaders making them work as one
 *
 * @author Clinton Begin
 */
public class ClassLoaderWrapper {

  /**
   * 应用指定的默认类加载器，本包可见字段
   */
  ClassLoader defaultClassLoader;
  /**
   * System ClassLoader，本包可见字段
   */
  ClassLoader systemClassLoader;

  /**
   * 本包可见构造器
   */
  ClassLoaderWrapper() {
    try {
      //初始化systemClassLoader字段
      systemClassLoader = ClassLoader.getSystemClassLoader();
    } catch (SecurityException ignored) {
      // AccessControlException on Google App Engine
    }
  }

  /**
   * Get a resource as a URL using the current class path
   *
   * @param resource - the resource to locate
   * @return the resource or null
   */
  public URL getResourceAsURL(String resource) {
    return getResourceAsURL(resource, getClassLoaders(null));
  }

  /**
   * Get a resource from the classpath, starting with a specific class loader
   *
   * @param resource    - the resource to find
   * @param classLoader - the first classloader to try
   * @return the stream or null
   */
  public URL getResourceAsURL(String resource, ClassLoader classLoader) {
    return getResourceAsURL(resource, getClassLoaders(classLoader));
  }

  /**
   * Get a resource from the classpath
   *
   * @param resource - the resource to find
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource) {
    return getResourceAsStream(resource, getClassLoaders(null));
  }

  /**
   * Get a resource from the classpath, starting with a specific class loader
   *
   * @param resource    - the resource to find
   * @param classLoader - the first class loader to try
   * @return the stream or null
   */
  public InputStream getResourceAsStream(String resource, ClassLoader classLoader) {
    return getResourceAsStream(resource, getClassLoaders(classLoader));
  }

  /**
   * Find a class on the classpath (or die trying)
   *
   * @param name - the class to look for
   * @return - the class
   * @throws ClassNotFoundException Duh.
   */
  public Class<?> classForName(String name) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(null));
  }

  /**
   * Find a class on the classpath, starting with a specific classloader (or die trying)
   *
   * @param name        - the class to look for
   * @param classLoader - the first classloader to try
   * @return - the class
   * @throws ClassNotFoundException Duh.
   */
  public Class<?> classForName(String name, ClassLoader classLoader) throws ClassNotFoundException {
    return classForName(name, getClassLoaders(classLoader));
  }

  //################################# 底层方法，调用ClassLoader的方法实现 ####################################################

  /**
   * Try to get a resource from a group of classloaders
   *
   * @param resource    - the resource to get
   * @param classLoader - the classloaders to examine
   * @return the resource or null
   */
  InputStream getResourceAsStream(String resource, ClassLoader[] classLoader) {
    //1.遍历 ClassLoader 数组
    for (ClassLoader cl : classLoader) {
      //2.ClassLoader元素不为null
      if (null != cl) {
        //2.1以绝对路径通过ClassLoader方法获得 InputStream
        // try to find the resource as passed
        InputStream returnValue = cl.getResourceAsStream(resource);
        //2.2以相对路径通过ClassLoader方法获得 InputStream
        // now, some class loaders want this leading "/", so we'll add it and try again if we didn't find the resource
        if (null == returnValue) {
          returnValue = cl.getResourceAsStream("/" + resource);
        }
        //2.3成功获得返回
        if (null != returnValue) {
          return returnValue;
        }
      }
    }
    return null;
  }

  /**
   * Get a resource as a URL using the current class path
   *
   * @param resource    - the resource to locate
   * @param classLoader - the class loaders to examine
   * @return the resource or null
   */
  URL getResourceAsURL(String resource, ClassLoader[] classLoader) {

    URL url;
    //1.遍历指定的classLoader数组
    for (ClassLoader cl : classLoader) {
      //2.数组中的ClassLoader元素不为null时
      if (null != cl) {
        //2.1调用 ClassLoader.getResource(resource)方法以 绝对路径 查找指定的资源
        // look for the resource as passed in...
        url = cl.getResource(resource);
        //2.2调用 ClassLoader.getResource(resource)方法以 相对路径 查找指定的资源
        // ...but some class loaders want this leading "/", so we'll add it
        // and try again if we didn't find the resource
        if (null == url) {
          url = cl.getResource("/" + resource);
        }
        //2.3查找到指定资源返回
        // "It's always in the last place I look for it!"
        // ... because only an idiot would keep looking for it after finding it, so stop looking already.
        if (null != url) {
          return url;
        }

      }

    }
    //3.未查找到返回null
    // didn't find it anywhere.
    return null;

  }

  /**
   * Attempt to load a class from a group of classloaders
   *
   * @param name        - the class to load
   * @param classLoader - the group of classloaders to examine
   * @return the class
   * @throws ClassNotFoundException - Remember the wisdom of Judge Smails: Well, the world needs ditch diggers, too.
   */
  Class<?> classForName(String name, ClassLoader[] classLoader) throws ClassNotFoundException {
    //1.遍历 ClassLoader 数组
    for (ClassLoader cl : classLoader) {
      //2.ClassLoader元素不为null
      if (null != cl) {

        try {
          //2.1通过此类加载器获得类
          Class<?> c = Class.forName(name, true, cl);
          //2.2成功获得到，返回
          if (null != c) {
            return c;
          }

        } catch (ClassNotFoundException e) {
          // we'll ignore this until all classloaders fail to locate the class
        }

      }

    }
    //3.获取不到抛出异常
    throw new ClassNotFoundException("Cannot find class: " + name);

  }

  /**
   * 返回类加载器数组，该数组指明了类加载器的使用顺序
   * @param classLoader 类加载器
   * @return
   */
  ClassLoader[] getClassLoaders(ClassLoader classLoader) {
    return new ClassLoader[]{
        classLoader, //参数指定的类加载器
        defaultClassLoader, //系统指定的默认类加载器
        Thread.currentThread().getContextClassLoader(), //当前线程绑定的类加载器
        getClass().getClassLoader(), //加载当前类所使用的类加载器
        systemClassLoader}; //SystemClassLoader
  }

}
