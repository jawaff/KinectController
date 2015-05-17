/*
This program is called "Kinect Controller". It is meant to detect gestures with the Kinect
and then simulate keyboard and/or mouse input. The configuration files used by this program are
not intended to be under the following license.

The Kinect Controller makes use of the J4K library and Esper and we have done
nothing to change their source.

By using J4K we are required to site their research article:
A. Barmpoutis. 'Tensor Body: Real-time Reconstruction of the Human Body and Avatar Synthesis from RGB-D',
IEEE Transactions on Cybernetics, Special issue on Computer Vision for RGB-D Sensors: Kinect and Its
Applications, October 2013, Vol. 43(5), Pages: 1347-1356.

By using Esper without their commercial license we are also required to release our software under
a GPL license.

Copyright (C) 2015  Jacob Waffle, Ryan Spiekerman and Josh Rogers

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

package com.lcsc.hackathon;

import com.lcsc.hackathon.events.*;
import com.lcsc.hackathon.listeners.*;

import java.io.File;

import com.espertech.esper.client.UpdateListener;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import java.io.FileReader;
import java.io.BufferedReader;

import java.io.IOException;
import java.io.FileNotFoundException;

import org.eclipse.jetty.util.ajax.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
    Important Note:
        This class should be restructured because it doesn't work as good as it could.
        It should in general be used to retrieve specific parts of the configuration file
        and then convert them into a map for use. Then based off of the rules and triggers
        it should be delegating responsibility to another class to deal with those rules
        and triggers in order to setup the program.
        
        The KeyPress and MouseMove triggers both required a different setup that couldn't easily
        be factored into the logic for this class. The setup is better off being executed by different
        classes for each of them. There also needs to be the ability to add custom logic into the
        configuration file. So that'll have to be figured out also.
*/


public class ConfigParser {
    private static final Logger _logger     = LoggerFactory.getLogger(ConfigParser.class);
    
    public ConfigParser() {}
    
    //This will parse the config file, pass listeners and patterns over to esper and
    //then it will return an EventFactry that is setup to relay information from the
    //kinect to Esper based on the config file.
    //
    //@param configFilename The filename of the config file. It's assumed to be in the
    //                      project's config directory.
    //@param eHandler       An EsperHandler object that should just be initialized and unchanged.
    //                      We're just going to configure it to trigger key presses and mouse movements
    //                      using listeners and patterns.
    //@return               This will be a configured EventFactory that is setup to relay information
    //                      from the kinect to Esper.
    public EventFactory parseConfigFile(String configFilename, EsperHandler eHandler) {
        String projRoot = new File("").getAbsolutePath();
        
        //Load the config file
        Map<String, Object> config = null;
        try {
            BufferedReader reader = new BufferedReader(new FileReader(new File(projRoot, "config/"+configFilename).getAbsolutePath()));
            config = (Map<String, Object>)JSON.parse(reader);
        } catch (FileNotFoundException e) {
            _logger.error("", e);
        } catch (IOException e) {
            _logger.error("", e);
            
        }
        
        EventFactory eFactory = new EventFactory();
        
        Object[] gestures = (Object[])config.get("gestures");
        
        for (Object gestureObj : gestures) {
            Map<String, Object> gesture = (Map<String, Object>)gestureObj;
            String gestureId = (String)gesture.get("id");
            
            Trigger trigger = null;
            
            //This is getting the trigger from the config file. This trigger will be stored
            //in the Triggers class and retrieved later by whichever listener is activated.
            //(We can't pass all the information we need through Esper.)
            Map<String, Object> triggerMap = (Map<String, Object>)gesture.get("trigger");
            String triggerType = (String)triggerMap.get("type");
            
            if (triggerType.equals("keyPress")) {
                List<Map<String, String>> keyDefs = new ArrayList<Map<String,String>>();
                
                Object[] keys = (Object[])triggerMap.get("keys");
                for (Object keyObj : keys) {
                    Map<String, String> key = (Map<String, String>)keyObj;
                    keyDefs.add(key);
                }
                
                trigger = new Trigger(triggerType, keyDefs);
            }
            else if (triggerType.equals("mouseMove")) {
                //keyDefs is going to be populated when we check out the actual rules.
                List<Map<String, String>> defs = new ArrayList<Map<String,String>>();
                trigger = new Trigger(triggerType, defs);
            }
            else {
                _logger.error(String.format("Invalid Trigger Type: %s", triggerType));
                System.exit(1);
            }
            
            //Then we will grab all of the rules and add Events to the EventFactory.
            //Then we'll be assembling a list of pattern chunks for the Esper pattern query.
            //(Those pattern chunks will be assembled later with the rest of the informatino needed.)
            List<String> rulePattern = new ArrayList<String>();
            
            int count = 0;
            Object[] rules = (Object[])gesture.get("rules");
            for (Object ruleObj : rules) {
                Map<String, Object> rule = (Map<String, Object>)ruleObj;
                String ruleType = (String)rule.get("type");
                
                try {
                    Map<String, String> attributes = (Map<String, String>)rule.get("attributes");
                    rulePattern.add(configureRule(eFactory, ruleType, triggerType, count, attributes));
                    count += 1;
                } catch (Exception e) {
                    _logger.error("", e);
                    System.exit(1);
                }
            }
            
            //The gestureId will identify the trigger and be accessible in the pattern.
            Triggers.addTrigger(gestureId, trigger);
            
            String pattern = "select ";
            
            if (triggerType.equals("mouseMove")) {
                pattern += String.format("'%s' as triggerId, '%s' as direction from pattern[", gestureId, (String)triggerMap.get("direction"));

                boolean x = false;
                boolean y = false;
                for (int i=0; i<rulePattern.size(); i++) {
                    String tmp = rulePattern.get(i);

                    _logger.info("TMP: "+tmp);
                    
                    if (tmp.contains("DistanceXRule") && !x) {
                        tmp = tmp.replaceAll("DistanceXRule", "every e1=DistanceXRule");
                        x = true;
                    }
                    else if (tmp.contains("DistanceYRule") && !y) {
                        tmp = tmp.replaceAll("DistanceYRule", "every e2=DistanceYRule");
                        y = true;
                    }
                    else {
                        tmp = "every "+tmp;
                    }
                    pattern += tmp;
                    if (i != rulePattern.size()-1) {
                        pattern += " or ";
                    }
                    else if (i == 0) {
                        pattern += " and ";
                    }
                    else {
                        pattern += "]";
                    }
                }
            }            
            else {
                pattern += String.format("'%s' as triggerId from pattern[",gestureId);

                for (int i=0; i<rulePattern.size(); i++) {
                    pattern += rulePattern.get(i);
                    if (i != rulePattern.size()-1) {
                        pattern += " and ";
                    }
                    else {
                        pattern += "]";
                    }
                }
            }

            _logger.info("Pattern:\n"+pattern);
            
            //Sets up the patterns and listeners for this gesture!
            eHandler.setPattern(gestureId, pattern);
            if (triggerType.equals("keyPress")) {
                eHandler.addListener(gestureId, (UpdateListener)new KeyPress());
            }
            else if (triggerType.equals("mouseMove")) {
                eHandler.addListener(gestureId, (UpdateListener)new MouseMove());
            }
        }
        
        return eFactory;
    }
    
    //This is for configuring the rules that are in the config file. Event objects need to be passed to
    //the EventFactory so that they can be updated later and passed to Esper.
    //@param eFactory       This is the guy that will relay information from the kinect to Esper. We're
    //                      passing it dummy Rule objects so that it knows what information it needs to
    //                      retrieve from the kinect.
    //@param ruleType       This will identify the rule that we're dealing with. It should be analogous to
    //                      the Java Bean that we're dealing with also.
    //@param triggerType    This gives information on how the each of the rules are added to the query.
    //@param attributes     This should have some data that will directly go into one of the Java beans for
    //                      Esper to deal with. There will also be thresholds and other information for
    //                      the Esper pattern. (ach rule will have some specific format.)
    //@return               This String is going to be placed within the Esper query pattern.
    private String configureRule(EventFactory eFactory, String ruleType, String triggerType, int index, Map<String, String> attributes) {
        String patternChunk = "";
        String patternChunk1 = "";
        String patternChunk2 = "";
        String patternChunk3 = "";
        if (ruleType.equals("AngleRule")) {
            String ruleId   = attributes.get("id");
            int end1 = Conversions.getJointId(attributes.get("endJoint1"));
            int end2 = Conversions.getJointId(attributes.get("endJoint2"));
            int vertex = Conversions.getJointId(attributes.get("vertex"));
            
            AngleRule angRule = new AngleRule(ruleId, end1, vertex, end2, -1);
            eFactory.addAngleRule(angRule);
            
            double minAngle = Double.parseDouble(attributes.get("min-angle"));
            double maxAngle = Double.parseDouble(attributes.get("max-angle"));
            
            patternChunk1 = String.format("AngleRule(end1=%d, vertex=%d, end2=%d, angle > %f, angle < %f)", end1, 
                                                                                                           vertex, 
                                                                                                           end2, 
                                                                                                           minAngle,
                                                                                                           maxAngle);
                                                                                                           
            patternChunk2 = String.format("AngleRule(end1=%d, vertex=%d, end2=%d, angle < %f)", end1, 
                                                                                                vertex, 
                                                                                                end2, 
                                                                                                minAngle);
                                                                                                           
            patternChunk3 = String.format("AngleRule(end1=%d, vertex=%d, end2=%d, angle > %f)", end1, 
                                                                                                vertex,
                                                                                                end2,
                                                                                                maxAngle);
        }
        else if (ruleType.equals("DistanceRule")) {
            String ruleId   = attributes.get("id");
            int joint1 = Conversions.getJointId(attributes.get("joint1"));
            int joint2 = Conversions.getJointId(attributes.get("joint2"));
            
            DistanceRule distRule = new DistanceRule(ruleId, joint1, joint2, -1);
            eFactory.addDistanceRule(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("DistanceRule(joint1=%d, joint2=%d, distance > %f, distance < %f)", joint1,
                                                                                                             joint2,
                                                                                                             minDist,
                                                                                                             maxDist);

            patternChunk2 = String.format("DistanceRule(joint1=%d, joint2=%d, distance < %f)", joint1,
                                                                                                      joint2,
                                                                                                      minDist);
                                                                                                             
            patternChunk3 = String.format("DistanceRule(joint1=%d, joint2=%d, distance > %f)", joint1,
                                                                                                      joint2,
                                                                                                      maxDist);
        }
        else if (ruleType.equals("DistanceXRule")) {
            String ruleId   = attributes.get("id");
            int joint1 = Conversions.getJointId(attributes.get("joint1"));
            int joint2 = Conversions.getJointId(attributes.get("joint2"));
            
            DistanceXRule distRule = new DistanceXRule(ruleId, joint1, joint2, 0);
            eFactory.addDistXRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("DistanceXRule(joint1=%d, joint2=%d, distance > %f, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("DistanceXRule(joint1=%d, joint2=%d, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("DistanceXRule(joint1=%d, joint1=%d, distance > %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        maxDist);
        }
        else if (ruleType.equals("DistanceYRule")) {
            String ruleId   = attributes.get("id");
            int joint1 = Conversions.getJointId(attributes.get("joint1"));
            int joint2 = Conversions.getJointId(attributes.get("joint2"));
            
            DistanceYRule distRule = new DistanceYRule(ruleId, joint1, joint2, 0);
            eFactory.addDistYRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("DistanceYRule(joint1=%d, joint2=%d, distance > %f, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("DistanceYRule(joint1=%d, joint2=%d, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("DistanceYRule(joint1=%d, joint2=%d, distance > %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        maxDist);
        }
        else if (ruleType.equals("DistanceZRule")) {
            String ruleId   = attributes.get("id");
            int joint1 = Conversions.getJointId(attributes.get("joint1"));
            int joint2 = Conversions.getJointId(attributes.get("joint2"));
            
            DistanceZRule distRule = new DistanceZRule(ruleId, joint1, joint2, 0);
            eFactory.addDistZRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("DistanceZRule(joint1=%d, joint2=%d, distance > %f, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("DistanceZRule(joint1=%d, joint2=%d, distance < %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("DistanceZRule(joint1=%d, joint2=%d, distance > %f)",
                                                                        joint1,
                                                                        joint2,
                                                                        maxDist);
        }
        else if (ruleType.equals("AbsoluteDistance")) {
            String ruleId   = attributes.get("id");
            int jointId = Conversions.getJointId(attributes.get("jointId"));
            
            double[] absolutePoint = new double[3];
            absolutePoint[0] = Double.parseDouble(attributes.get("absoluteX"));
            absolutePoint[1] = Double.parseDouble(attributes.get("absoluteY"));
            absolutePoint[2] = Double.parseDouble(attributes.get("absoluteZ"));
            
            AbsoluteDistance distRule = new AbsoluteDistance(ruleId, absolutePoint, jointId, 0);
            eFactory.addAbsDistRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("AbsoluteDistance(jointId=%d, absPoint[0]=%f, absPoint[1]=%f, absPoint[2]=%f, distance > %f, distance < %f)",
                                                                        jointId,
                                                                        absolutePoint[0],
                                                                        absolutePoint[1],
                                                                        absolutePoint[2],
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("AbsoluteDistance(jointId=%d, absPoint[0]=%f, absPoint[1]=%f, absPoint[2]=%f, distance < %f)",
                                                                        jointId,
                                                                        absolutePoint[0],
                                                                        absolutePoint[1],
                                                                        absolutePoint[2],
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("AbsoluteDistance(jointId=%d, absPoint[0]=%f, absPoint[1]=%f, absPoint[2]=%f, distance > %f)",
                                                                        jointId,
                                                                        absolutePoint[0],
                                                                        absolutePoint[1],
                                                                        absolutePoint[2],
                                                                        maxDist);
        }
        else if (ruleType.equals("AbsoluteDistX")) {
            String ruleId   = attributes.get("id");
            int jointId = Conversions.getJointId(attributes.get("jointId"));
            
            double absPointX = Double.parseDouble(attributes.get("absoluteX"));
            
            AbsoluteDistX distRule = new AbsoluteDistX(ruleId, absPointX, jointId, 0);
            eFactory.addAbsDistXRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("AbsoluteDistX(jointId=%d, absPointX=%f, distance > %f, distance < %f)",
                                                                        jointId,
                                                                        absPointX,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("AbsoluteDistX(jointId=%d, absPointX=%f, distance < %f)",
                                                                        jointId,
                                                                        absPointX,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("AbsoluteDistX(jointId=%d, absPointX=%f, distance > %f)",
                                                                        jointId,
                                                                        absPointX,
                                                                        maxDist);
        }
        else if (ruleType.equals("AbsoluteDistY")) {
            String ruleId   = attributes.get("id");
            int jointId = Conversions.getJointId(attributes.get("jointId"));
            
            double absoluteY = Double.parseDouble(attributes.get("absoluteY"));
            
            AbsoluteDistY distRule = new AbsoluteDistY(ruleId, absoluteY, jointId, 0);
            eFactory.addAbsDistYRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("AbsoluteDistY(jointId=%d, absPointY=%f, distance > %f, distance < %f)",
                                                                        jointId,
                                                                        absoluteY,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("AbsoluteDistY(jointId=%d, absPointY=%f, distance < %f)",
                                                                        jointId,
                                                                        absoluteY,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("AbsoluteDistY(jointId=%d, absPointY=%f, distance > %f)",
                                                                        jointId,
                                                                        absoluteY,
                                                                        maxDist);
        }
        else if (ruleType.equals("AbsoluteDistZ")) {
            String ruleId   = attributes.get("id");
            int jointId = Conversions.getJointId(attributes.get("jointId"));
            
            double absoluteZ = Double.parseDouble(attributes.get("absoluteZ"));
            
            AbsoluteDistZ distRule = new AbsoluteDistZ(ruleId, absoluteZ, jointId, 0);
            eFactory.addAbsDistZRules(distRule);
            
            double minDist = Double.parseDouble(attributes.get("min-dist"));
            double maxDist = Double.parseDouble(attributes.get("max-dist"));
            
            patternChunk1 = String.format("AbsoluteDistZ(jointId=%d, absPointZ=%f, distance > %f, distance < %f)",
                                                                        jointId,
                                                                        absoluteZ,
                                                                        minDist,
                                                                        maxDist);

            patternChunk2 = String.format("AbsoluteDistZ(jointId=%d, absPointZ=%f, distance < %f)",
                                                                        jointId,
                                                                        absoluteZ,
                                                                        minDist);
                                                                                                             
            patternChunk3 = String.format("AbsoluteDistZ(jointId=%d, absPointZ=%f, distance > %f)",
                                                                        jointId,
                                                                        absoluteZ,
                                                                        maxDist);
        }
        else {
            _logger.error("RuleType is invalid: "+ruleType);
            System.exit(1);
        }
        
        if (triggerType.equals("keyPress")) {
            patternChunk = String.format("every ((%s or %s) -> %s)", patternChunk3, patternChunk2, patternChunk1);
        }
        else if (triggerType.equals("mouseMove")) {
            patternChunk = String.format("%s", patternChunk1);
        }
        else {
            _logger.error("Invalid Trigger Type: "+triggerType);
            System.exit(1);
        }
        
        return patternChunk;
    }
}
