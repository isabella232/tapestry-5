// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.tapestry5.internal.services;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.apache.tapestry5.SymbolConstants;
import org.apache.tapestry5.annotations.OnEvent;
import org.apache.tapestry5.annotations.StaticActivationContextValue;
import org.apache.tapestry5.commons.Messages;
import org.apache.tapestry5.http.services.BaseURLSource;
import org.apache.tapestry5.internal.InternalConstants;
import org.apache.tapestry5.internal.structure.Page;
import org.apache.tapestry5.ioc.services.SymbolSource;
import org.apache.tapestry5.ioc.services.ThreadLocale;
import org.apache.tapestry5.json.JSONArray;
import org.apache.tapestry5.json.JSONObject;
import org.apache.tapestry5.model.ComponentModel;
import org.apache.tapestry5.runtime.Component;
import org.apache.tapestry5.services.ComponentClassResolver;
import org.apache.tapestry5.services.ComponentSource;
import org.apache.tapestry5.services.OpenApiDescriptionGenerator;
import org.apache.tapestry5.services.PageRenderLinkSource;
import org.apache.tapestry5.services.messages.ComponentMessagesSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@linkplain OpenApiDescriptionGenerator} that generates lots, if not most, of the application's 
 * OpenAPI 3.0 documentation.
 * 
 * @since 5.8.0
 */
public class DefaultOpenApiDescriptionGenerator implements OpenApiDescriptionGenerator 
{
    
    final private static Logger LOGGER = LoggerFactory.getLogger(DefaultOpenApiDescriptionGenerator.class);
    
    final private BaseURLSource baseUrlSource;
    
    final private SymbolSource symbolSource;
    
    final private ComponentMessagesSource componentMessagesSource;
    
    final private ThreadLocale threadLocale;
    
    final private PageSource pageSource;
    
    final private ThreadLocal<Messages> messages;
    
    final private ComponentClassResolver componentClassResolver;
    
    final private Set<String> failedPageNames;
    
    final private PageRenderLinkSource pageRenderLinkSource;
    
    final private ComponentSource componentSource;
    
    final private static String KEY_PREFIX = "openapi.";
    
    public DefaultOpenApiDescriptionGenerator(
            final BaseURLSource baseUrlSource, 
            final SymbolSource symbolSource, 
            final ComponentMessagesSource componentMessagesSource,
            final ThreadLocale threadLocale,
            final PageSource pageSource,
            final ComponentClassResolver componentClassResolver,
            final PageRenderLinkSource pageRenderLinkSource,
            final ComponentSource componentSource) 
    {
        super();
        this.baseUrlSource = baseUrlSource;
        this.symbolSource = symbolSource;
        this.componentMessagesSource = componentMessagesSource;
        this.threadLocale = threadLocale;
        this.pageSource = pageSource;
        this.componentClassResolver = componentClassResolver;
        this.pageRenderLinkSource = pageRenderLinkSource;
        this.componentSource = componentSource;
        messages = new ThreadLocal<>();
        failedPageNames = new HashSet<>();
    }

    @Override
    public JSONObject generate(JSONObject documentation) 
    {

        // Making sure all pages have been loaded and transformed
        for (String pageName : componentClassResolver.getPageNames())
        {
            if (!failedPageNames.contains(pageName))
            {
                try
                {
                    pageSource.getPage(pageName);
                }
                catch (Exception e)
                {
                    // Ignoring exception, since some classes may not
                    // be instantiable.
                    failedPageNames.add(pageName);
                }
            }
        }

        messages.set(componentMessagesSource.getApplicationCatalog(threadLocale.getLocale()));

        if (documentation == null)
        {
            documentation = new JSONObject();
        }
        
        documentation.put("openapi", symbolSource.valueForSymbol(SymbolConstants.OPENAPI_VERSION));
        
        generateInfo(documentation);
        
        JSONArray servers = new JSONArray();
        servers.add(new JSONObject("url", baseUrlSource.getBaseURL(false)));
        servers.add(new JSONObject("url", baseUrlSource.getBaseURL(true)));
        
        documentation.put("servers", servers);
        
        try
        {
            addPaths(documentation);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        
        return documentation;
        
    }

    private void generateInfo(JSONObject documentation) {
        JSONObject info = new JSONObject();
        putIfNotEmpty(info, "title", SymbolConstants.OPENAPI_TITLE);
        putIfNotEmpty(info, "description", SymbolConstants.OPENAPI_DESCRIPTION);
        info.put("version", getValueFromSymbol(SymbolConstants.OPENAPI_APPLICATION_VERSION).orElse("?"));
        documentation.put("info", info);
    }
    
    private void addPaths(JSONObject documentation) throws NoSuchMethodException, SecurityException 
    {
        
        List<Page> pagesWithRestEndpoints = pageSource.getAllPages().stream()
                .filter(DefaultOpenApiDescriptionGenerator::hasRestEndpoint)
                .collect(Collectors.toList());
        
        JSONObject paths = new JSONObject();
        JSONArray tags = new JSONArray();
        
        for (Page page : pagesWithRestEndpoints) 
        {
            processPageClass(page, paths, tags);
        }
        
        documentation.put("tags", tags);
        documentation.put("paths", paths);
        
    }

    private void processPageClass(Page page, JSONObject paths, JSONArray tags) throws NoSuchMethodException {
        final Class<?> pageClass = page.getRootComponent().getClass();

        final String tagName = addPageTag(tags, pageClass);
        
        ComponentModel model = page.getRootComponent().getComponentResources().getComponentModel();
        
        JSONArray methodsAsJson = getMethodsAsJson(model);
        
        List<Method> methods = toMethods(methodsAsJson, pageClass);
        
        for (Method method : methods) 
        {
            processMethod(method, pageClass, paths, tagName);
        }
    }

    private String addPageTag(JSONArray tags, final Class<?> pageClass) 
    {
        final String tagName = getValue(pageClass, "tag.name").orElse(pageClass.getSimpleName());
        JSONObject tag = new JSONObject();
        tag.put("name", tagName);
        putIfNotEmpty(tag, "description", getValue(pageClass, "tag.description"));
        tags.add(tag);
        return tagName;
    }

    private JSONArray getMethodsAsJson(ComponentModel model) 
    {
        JSONArray methodsAsJson = new JSONArray();
        while (model != null)
        {
            JSONArray thisMethodArray = new JSONArray(model.getMeta(
                    InternalConstants.REST_ENDPOINT_EVENT_HANDLER_METHODS));
            addElementsIfNotPresent(methodsAsJson, thisMethodArray);
            model = model.getParentModel();
        }
        return methodsAsJson;
    }

    private void processMethod(Method method, final Class<?> pageClass, JSONObject paths, final String tagName) 
    {
        final String uri = getPath(method, pageClass);
        final JSONObject path;
        if (paths.containsKey(uri))
        {
            path = paths.getJSONObject(uri);
        }
        else
        {
            path = new JSONObject();
            paths.put(uri, path);
        }
        
        final String httpMethod = getHttpMethod(method);
        
        if (path.containsKey(httpMethod))
        {
            throw new RuntimeException(String.format(
                    "There are at least two different REST endpoints for path %s and HTTP method %s in class %s",
                    uri, httpMethod, pageClass.getName()));
        }
        else
        {
            
            final JSONObject methodDocumentation = new JSONObject();
            
            putIfNotEmpty(methodDocumentation, "summary", getValue(method, uri, httpMethod, "summary"));
            putIfNotEmpty(methodDocumentation, "description", getValue(method, uri, httpMethod, "description"));
            
            JSONArray methodTags = new JSONArray();
            methodTags.add(tagName);
            methodDocumentation.put("tags", methodTags);
            
            JSONObject responses = new JSONObject();
            JSONObject defaultResponse = new JSONObject();
            int statusCode = httpMethod.equals("post") ? 
                    HttpServletResponse.SC_CREATED : HttpServletResponse.SC_OK;
            putIfNotEmpty(defaultResponse, "description", getValue(method, uri, httpMethod, statusCode));
            responses.put(String.valueOf(statusCode), defaultResponse);
            
            methodDocumentation.put("responses", responses);
            
            path.put(httpMethod, methodDocumentation);
        }
    }

    private void addElementsIfNotPresent(JSONArray accumulator, JSONArray array) 
    {
        if (array != null)
        {
            for (int i = 0; i < array.size(); i++)
            {
                JSONObject method = array.getJSONObject(i);
                boolean present = isPresent(accumulator, method);
                if (!present)
                {
                    accumulator.add(method);
                }
            }
        }
    }

    private boolean isPresent(JSONArray array, JSONObject object) 
    {
        boolean present = false;
        for (int i = 0; i < array.size(); i++)
        {
            if (object.equals(array.getJSONObject(i)))
            {
                present = false;
            }
        }
        return present;
    }

    private Optional<String> getValue(Class<?> clazz, String property) 
    {
        Optional<String> value = getValue(
                KEY_PREFIX + clazz.getName() + "." + property);
        if (!value.isPresent())
        {
            value = getValue(
                    KEY_PREFIX + clazz.getSimpleName() + "." + property);
        }
        return value;
    }
    
    private Optional<String> getValue(Method method, String path, String httpMethod, String property) 
    {
        return getValue(method, path + "." + httpMethod + "." + property, true);
    }
    
    public Optional<String> getValue(Method method, String path, String httpMethod, int statusCode) 
    {
        Optional<String> value = getValue(method, path + "." + httpMethod + ".response." + String.valueOf(statusCode), true);
        if (!value.isPresent())
        {
            value = getValue(method, httpMethod + ".response." + String.valueOf(statusCode), false);
        }
        if (!value.isPresent())
        {
            value = getValue(method, "response." + String.valueOf(statusCode), false);
        }
        if (!value.isPresent())
        {
            value = getValue("response." + String.valueOf(statusCode));
        }
        return value;
    }

    public Optional<String> getValue(Method method, final String suffix, final boolean skipClassNameLookup) 
    {
        Optional<String> value = Optional.empty();

        if (!skipClassNameLookup)
        {
            value = getValue(
                    KEY_PREFIX + method.getDeclaringClass().getName() + "." + suffix);
            if (!value.isPresent())
            {
                value = getValue(
                        KEY_PREFIX + method.getDeclaringClass().getSimpleName() + "." + suffix);
            }
        }
        if (!value.isPresent())
        {
            value = getValue(KEY_PREFIX + suffix);
        }
        return value;
    }

    private List<Method> toMethods(JSONArray methodsAsJson, Class<?> pageClass) throws NoSuchMethodException, SecurityException 
    {
        List<Method> methods = new ArrayList<>(methodsAsJson.size());
        for (Object object : methodsAsJson)
        {
            JSONObject methodAsJason = (JSONObject) object;
            final String name = methodAsJason.getString("name");
            final JSONArray parametersAsJson = methodAsJason.getJSONArray("parameters");
            @SuppressWarnings("rawtypes")
            List<Class> parameterTypes = parametersAsJson.stream()
                .map(o -> ((String) o))
                .map(s -> toClass(s))
                .collect(Collectors.toList());
            methods.add(findMethod(pageClass, name, parameterTypes));
        }
        return methods;
    }

    @SuppressWarnings("rawtypes")
    public Method findMethod(Class<?> pageClass, final String name, List<Class> parameterTypes) throws NoSuchMethodException 
    {
        Method method = null;
        try
        {
            method = pageClass.getDeclaredMethod(name, 
                    parameterTypes.toArray(new Class[parameterTypes.size()]));
        }
        catch (NoSuchMethodException e)
        {
            // Let's try the supertypes
            List<Class> superTypes = new ArrayList<>();
            superTypes.add(pageClass.getSuperclass());
            superTypes.addAll((Arrays.asList(pageClass.getInterfaces())));
            for (Class clazz : superTypes)
            {
                method = findMethod(clazz, name, parameterTypes);
                if (method != null)
                {
                    break;
                }
            }
        }
        return method;
    }
    
    private static Class<?> toClass(String string)
    {
        try {
            return Class.forName(string);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private String getPath(Method method, Class<?> pageClass)
    {
        final StringBuilder builder = new StringBuilder();
        builder.append(pageRenderLinkSource.createPageRenderLink(pageClass).toString());
        for (Parameter parameter : method.getParameters())
        {
            if (!isIgnored(parameter))
            {
                builder.append("/");
                final StaticActivationContextValue staticValue = parameter.getAnnotation(StaticActivationContextValue.class);
                if (staticValue != null)
                {
                    builder.append(staticValue.value());
                }
                else
                {
                    builder.append("{");
                    builder.append(parameter.getName());
                    builder.append("}");
                }
            }
        }
        return builder.toString();
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static boolean isIgnored(Parameter parameter)
    {
        boolean ignored = false;
        for (Class clazz : InternalConstants.INJECTED_PARAMETERS)
        {
            if (parameter.getAnnotation(clazz) != null)
            {
                ignored = true;
                break;
            }
        }
        return ignored;
    }

    private void putIfNotEmpty(JSONObject object, String propertyName, Optional<String> value)
    {
        value.ifPresent((v) -> object.put(propertyName, v));
    }
    
    private void putIfNotEmpty(JSONObject object, String propertyName, String key)
    {
        getValue(key).ifPresent((value) -> object.put(propertyName, value));
    }
    
    private Optional<String> getValue(String key)
    {
        Optional<String> value = getValueFromMessages(key);
        return value.isPresent() ? value : getValueFromSymbol(key);
    }

    private Optional<String> getValueFromMessages(String key)
    {
        logMessageLookup(key);
        final String value = messages.get().get(key.replace("tapestry.", "")).trim();
        return value.startsWith("[") && value.endsWith("]") ? Optional.empty() : Optional.of(value);
    }

    private void logSymbolLookup(String key) {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("Looking up symbol  " + key);
        }
    }
    
    private void logMessageLookup(String key) {
        if (LOGGER.isDebugEnabled())
        {
            LOGGER.debug("Looking up message " + key);
        }
    }
    
    private Optional<String> getValueFromSymbol(String key)
    {
        String value;
        final String symbol = "tapestry." + key;
        logSymbolLookup(symbol);
        try
        {
            value = symbolSource.valueForSymbol(symbol);
        }
        catch (RuntimeException e)
        {
            // value not found;
            value = null;
        }
        return Optional.ofNullable(value);
    }
    
    private static final String PREFIX = InternalConstants.HTTP_METHOD_EVENT_PREFIX.toLowerCase();
    
    private static String getHttpMethod(Method method)
    {
        String httpMethod;
        OnEvent onEvent = method.getAnnotation(OnEvent.class);
        if (onEvent != null)
        {
            httpMethod = onEvent.value();
        }
        else
        {
            httpMethod = method.getName().replace("on", "");
        }
        httpMethod = httpMethod.toLowerCase();
        httpMethod = httpMethod.replace(PREFIX, "");
        return httpMethod;
    }

    private static boolean hasRestEndpoint(Page page) 
    {
        return hasRestEndpoint(page.getRootComponent());
    }

    private static boolean hasRestEndpoint(final Component component) 
    {
        final ComponentModel componentModel = component.getComponentResources().getComponentModel();
        return InternalConstants.TRUE.equals(componentModel.getMeta(
                InternalConstants.REST_ENDPOINT_EVENT_HANDLER_METHOD_PRESENT));
    }

}
