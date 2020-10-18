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
package org.apache.tapestry5.versionmigrator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main 
{

    public static void main(String[] args) 
    {
        if (args.length == 0)
        {
            printHelp();
        }
        else 
        {
            switch (args[0])
            {
                case "generate": 
                    createVersionFile(args[1]);
                    break;
                    
                default:
                    printHelp();
            }
        }
    }
    
    private static void printHelp() {
        // TODO Auto-generated method stub
        
    }

    private static void createVersionFile(String versionNumber) 
    {
        final TapestryVersion tapestryVersion = Arrays.stream(TapestryVersion.values())
            .filter(v -> versionNumber.equals(v.getNumber()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Tapestry version: " + versionNumber + ". "));
        createVersionFile(tapestryVersion);
    }

    private static void createVersionFile(TapestryVersion version) 
    {
        final String commandLine = String.format("git diff --summary %s %s", 
                version.getPreviousVersionGitHash(), version.getVersionGitHash());
        final Process process;
        final Map<String, String> renames = new HashMap<>();
        
        System.out.printf("Running command line '%s'\n", commandLine);
        List<String> lines = new ArrayList<>();
        try 
        {
            process = Runtime.getRuntime().exec(commandLine);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (
            final InputStream inputStream = process.getInputStream();
            final InputStreamReader isr = new InputStreamReader(inputStream);
            final BufferedReader reader = new BufferedReader(isr)) 
        {
            String line = reader.readLine();
            while (line != null)
            {
                lines.add(line);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        process(lines, renames);
    }

    private static void process(List<String> lines, Map<String, String> renames) {
        lines = lines.stream()
            .map(s -> s.trim())
            .filter(s -> s.startsWith("rename"))
            .filter(s -> !s.contains("test"))
            .filter(s -> !s.contains("package-info"))
            .map(s -> s.replaceFirst("rename", "").trim())
            .collect(Collectors.toList());
        
        for (String line : lines) {
            if (line.startsWith("{")) {
                
            }
            else {
                Pattern pattern = Pattern.compile("([^/]*)" + Pattern.quote("/") + "(.*)" + Pattern.quote("{") + "(.*)\\s=>\\s(.*)" + Pattern.quote("}/") + "([^\\.]*).*");
                
                final Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    System.out.printf("%s %s %s %s %s\n", matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), matcher.group(5));
                }
            }
        }
    }

}
