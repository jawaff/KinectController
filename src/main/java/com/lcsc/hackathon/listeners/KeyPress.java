package com.lcsc.hackathon.listeners;

import com.espertech.esper.client.UpdateListener;
import com.espertech.esper.client.EventBean;

import java.awt.Robot;
import java.awt.AWTException;

public class KeyPress implements UpdateListener {
    private Robot rob;
    
    public KeyPress() {
        try {
            this.rob = new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }
    
    public void update(EventBean[] newEvents, EventBean[] oldEvents) {
        this.rob.keyPress(((Integer)newEvents[0].get("keyID")).intValue());
        this.rob.keyRelease(((Integer)newEvents[0].get("keyID")).intValue());
    }
}