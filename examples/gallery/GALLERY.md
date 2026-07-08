# Sirentide gallery — real-browser renders

Captured by [BrewShot](https://github.com/supsup/BrewShot) in the test suite. Each diagram is audited so no drawn element escapes its canvas (the visual class the byte-pinned SVG goldens can't see).

## Pie

```
pie
"Reviews" : 40
"Builds" : 25
"Docs" : 20
"Design" : 15
```

![Pie](pie.png)

## Pie with a legend

```
pie legend
"Reviews" : 40
"Builds" : 25
"Docs" : 20
"Design" : 15
```

![Pie with a legend](pie-legend.png)

## Custom per-item colours

```
pie legend
"Ship" : 40 #22c55e
"WIP" : 25 #eab308
"Blocked" : 20 #ef4444
"Backlog" : 15 #64748b
```

![Custom per-item colours](pie-custom.png)

## Bar chart (signed axis)

```
xychart
"Mon" : 5
"Tue" : -3
"Wed" : 8
"Thu" : 6
```

![Bar chart (signed axis)](xychart.png)

## Line chart (two series + legend)

```
xychart line legend
series: Revenue, Cost
"Q1" : 5 8
"Q2" : 8 4
"Q3" : 3 6
"Q4" : 9 2
```

![Line chart (two series + legend)](xychart-line.png)

## Timeline (proportional)

```
timeline
"Founded" : 2000
"Series A" : 2005
"Launch" : 2020
```

![Timeline (proportional)](timeline.png)

## Gantt

```
gantt
"Design" : 0-3
"Build" : 3-8
"Test" : 7-11
"Ship" : 11-13
```

![Gantt](gantt.png)

## Flowchart (layered, custom node colour)

```
flowchart
A[Open PR] --> B{Approve?}
B -->|yes| C[Merge] #22c55e
B -->|no| D[Revise]
D -->|re-review| B
```

![Flowchart (layered, custom node colour)](flowchart.png)

## Flowchart (nested subgraph clusters)

```
flowchart TD
A[Start] --> B[Work]
subgraph outer [Build Pipeline]
B --> C[Compile]
subgraph inner [Test Suite]
C --> D[Unit]
D --> F[Integration]
end
F --> G[Package]
end
G --> E[Ship]
```

![Flowchart (nested subgraph clusters)](flowchart-subgraph.png)

## Sequence (API token flow)

```
sequence
Client ->> Gateway : GET /token
Gateway ->> Auth : validate
Auth -->> Gateway : ok
Gateway ->> Gateway : sign JWT
Gateway -->> Client : 200 token
```

![Sequence (API token flow)](sequence.png)

## Sequence (alt / loop / par frames)

```
sequence
Alice ->> Bob : hello
alt is available
Bob -->> Alice : yes
loop every retry
Alice ->> Bob : ping
end
else is busy
Bob -->> Alice : later
end
par to Bob
Alice ->> Bob : a
and to Carol
Alice ->> Carol : b
end
```

![Sequence (alt / loop / par frames)](sequence-blocks.png)

## State diagram (lifecycle)

```
state
[*] --> Idle
Idle --> Running : start
Running --> Idle : stop
Running --> [*]
```

![State diagram (lifecycle)](state.png)

## Quadrant chart (2×2 prioritization matrix)

```
quadrant
x-axis "Low Reach" --> "High Reach"
y-axis "Low Impact" --> "High Impact"
quadrant-1 "Major project"
quadrant-2 "Quick win"
quadrant-3 "Deprioritize"
quadrant-4 "Fill-in"
"Feature A" : [0.3, 0.6]
"Feature B" : [0.75, 0.8]
"Feature C" : [0.5, 0.2]
"Feature D" : [0.85, 0.35]
```

![Quadrant chart (2×2 prioritization matrix)](quadrant.png)

## Pie thin-slice outside labels (clipped)

```
pie
"quarter" : 25
"right outside label that should clip" : 1
"rest" : 74
```

![Pie thin-slice outside labels (clipped)](pie-thin-labels.png)

## Timeline endpoint labels (clamped)

```
timeline
"very long left endpoint label" : 0
"very long right endpoint label" : 10
```

![Timeline endpoint labels (clamped)](timeline-endpoints.png)

## Flowchart left-going edge label (clamped)

```
flowchart
A --> C
B -->|this forward label can escape left| C
```

![Flowchart left-going edge label (clamped)](flowchart-left-label.png)

