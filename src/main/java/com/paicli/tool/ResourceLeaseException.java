package com.paicli.tool;

import com.paicli.policy.PolicyException;

public class ResourceLeaseException extends PolicyException {
    public ResourceLeaseException(String message) {
        super(message);
    }
}
