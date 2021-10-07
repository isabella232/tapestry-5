// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.apache.tapestry5.integration.app1.base;

import org.apache.tapestry5.EventConstants;
import org.apache.tapestry5.annotations.OnEvent;
import org.apache.tapestry5.annotations.StaticActivationContextValue;
import org.apache.tapestry5.http.services.Response;
import org.apache.tapestry5.util.TextStreamResponse;

public class BaseRestDemoPage {
    
    public static final String EXTRA_HTTP_HEADER = "X-Event";
    
    public static final String SUBPATH = "something";
    
    final protected static TextStreamResponse createResponse(String eventName, String body, String parameter)
    {
        String content = eventName + ":" + parameter + (body == null ? "" : ":" + body);
        return new TextStreamResponse("text/plain", content) 
        {
            @Override
            public void prepareResponse(Response response) {
                super.prepareResponse(response);
                response.addHeader(EXTRA_HTTP_HEADER, eventName);
            }
            
        };
    }
    
    @OnEvent(EventConstants.HTTP_GET)
    protected Object superclassEndpoint(@StaticActivationContextValue("superclassEndpoint") String parameter)
    {
        return new TextStreamResponse("text/plain", parameter);
    }
    
}
