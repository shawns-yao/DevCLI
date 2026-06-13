package com.devcli.tool;

import com.devcli.policy.PolicyException;

public class ResourceLeaseException extends PolicyException {
    public ResourceLeaseException(String message) {
        super(message);
    }
}
