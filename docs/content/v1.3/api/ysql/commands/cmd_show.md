---
title: SHOW
linkTitle: SHOW
summary: Display the value of a run-time parameter.
description: SHOW
menu:
  v1.3:
    identifier: api-ysql-commands-show
    parent: api-ysql-commands
isTocNested: true
showAsideToc: true
---

## Synopsis

Use the `SHOW` statement to display the value of a run-time parameter.

## Syntax

<ul class="nav nav-tabs nav-tabs-yb">
  <li >
    <a href="#grammar" class="nav-link active" id="grammar-tab" data-toggle="tab" role="tab" aria-controls="grammar" aria-selected="true">
      <i class="fas fa-file-alt" aria-hidden="true"></i>
      Grammar
    </a>
  </li>
  <li>
    <a href="#diagram" class="nav-link" id="diagram-tab" data-toggle="tab" role="tab" aria-controls="diagram" aria-selected="false">
      <i class="fas fa-project-diagram" aria-hidden="true"></i>
      Diagram
    </a>
  </li>
</ul>

<div class="tab-content">
  <div id="grammar" class="tab-pane fade show active" role="tabpanel" aria-labelledby="grammar-tab">
    {{% includeMarkdown "../syntax_resources/commands/show_stmt.grammar.md" /%}}
  </div>
  <div id="diagram" class="tab-pane fade" role="tabpanel" aria-labelledby="diagram-tab">
    {{% includeMarkdown "../syntax_resources/commands/show_stmt.diagram.md" /%}}
  </div>
</div>

## Semantics

- Although the values of a parameter can be set, displayed, and reset, the effect of these parameters are not yet supported in YugaByte. The factory-settings or default behaviors will be used for the moment.

### *configuration_parameter*

Specify the name of the parameter to be displayed.

### ALL

Show the values of all configuration parameters, with descriptions.

## See also

- [`SET`](../cmd_set)
- [`RESET`](../cmd_reset)
