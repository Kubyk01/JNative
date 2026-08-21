package io.github.kubyk01.domain.analyzer.aliasanalysis;

import lombok.Getter;

import java.util.HashSet;
import java.util.Set;

@Getter
public class PointsToSet {
    private final Set<AllocationSite> sites = new HashSet<>();

    public void add(AllocationSite site) {
        sites.add(site);
    }

    public void addAll(PointsToSet other) {
        sites.addAll(other.sites);
    }

    public boolean contains(AllocationSite site) {
        return sites.contains(site);
    }

    public boolean isEmpty() {
        return sites.isEmpty();
    }

    public PointsToSet copy() {
        PointsToSet copy = new PointsToSet();
        copy.sites.addAll(this.sites);
        return copy;
    }

    public boolean intersects(PointsToSet other) {
        for (AllocationSite s : sites) {
            if (other.sites.contains(s)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return sites.toString();
    }
}
