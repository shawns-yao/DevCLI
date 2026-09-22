package com.devcli.hitl;

import java.util.Objects;
import java.util.Set;

/**
 * Delegates HITL interaction to the currently active UI implementation.
 *
 * <p>The registry is created before the UI mode is selected, so this wrapper
 * lets CLI swap renderer-backed handlers without rebuilding the tool registry.
 */
public final class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public HitlHandler getDelegate() {
        return delegate;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        return delegate.requestApproval(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public Set<String> approvedAllTools() {
        return delegate.approvedAllTools();
    }

    @Override
    public Set<String> approvedAllServers() {
        return delegate.approvedAllServers();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }

    @Override
    public void onTaskGrantAllow(String toolName, String reason) {
        delegate.onTaskGrantAllow(toolName, reason);
    }
}
