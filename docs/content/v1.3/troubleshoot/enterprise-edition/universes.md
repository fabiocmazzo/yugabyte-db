---
title: Troubleshoot universes
linkTitle: Troubleshoot universes
description: Troubleshoot universes
menu:
  v1.3:
    identifier: troubleshoot-universes
    parent: troubleshoot-enterprise-edition
    weight: 853
isTocNested: true
showAsideToc: true
---

## Metrics page

In the [Admin Console](../../../deploy/enterprise-edition/install-admin-console/), click on the [Universe](../../../architecture/concepts/universe/#universe) page, then go to the Metrics tab.
The page shows a number of interactive metrics graphs that capture the state of the YugaByte Universe over time.

![YugaByte Metrics Page](/images/troubleshooting/check-metrics.png)

Note: For a quick overview, check the query ops and latency graphs as well as the CPU, memory, disk, and network usage graphs. In case of dips or spikes the other graphs will offer additional information that can help diagnose the issue.

## Nodes status

In the [Admin Console](../../../deploy/enterprise-edition/install-admin-console/), click on the [Universe](../../../architecture/concepts/universe/#universe) page, then go to the Nodes tab.
The page will show the status of the Master and TServer on each YugaByte node.

![YugaByte Nodes Page](/images/troubleshooting/check-node-status.png)

In case of issues, more information about each Master or TServer is available on its respective Details page.
Generally the link is: `<node-ip>:7000` for Masters and `<node-ip>:9000` for TServers.

Note that in some setups these links may not be accessible, depending on the configuration of your on-premises datacenter or cloud-provider account. To fix this, read more [here](../../nodes/check-processes/).
