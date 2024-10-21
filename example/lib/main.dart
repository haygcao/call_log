import 'dart:async';

import 'package:call_log/call_log.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart'; // 添加 SharedPreferences
import 'package:sim_card_info/sim_card_info.dart';
import 'package:sim_card_info/sim_info.dart' as flutter;
import 'package:workmanager/workmanager.dart';

///TOP-LEVEL FUNCTION PROVIDED FOR WORK MANAGER AS CALLBACK
void callbackDispatcher() {
  Workmanager().executeTask((dynamic task, dynamic inputData) async {
    print('Background Services are Working!');
    try {
      final Iterable<CallLogEntry> cLog = await CallLog.get();
      print('Queried call log entries');
      for (CallLogEntry entry in cLog) {
        print('-------------------------------------');
        print('F. NUMBER  : ${entry.formattedNumber}');
        print('C.M. NUMBER: ${entry.cachedMatchedNumber}');
        print('NUMBER     : ${entry.number}');
        print('NAME       : ${entry.name}');
        print('TYPE       : ${entry.callType}');
        print(
            'DATE       : ${DateTime.fromMillisecondsSinceEpoch(entry.timestamp!)}');
        print('DURATION   : ${entry.duration}');
        print('ACCOUNT ID : ${entry.phoneAccountId}');
        print('SIM NAME   : ${entry.simDisplayName}');
        print('-------------------------------------');
      }
      return true;
    } on PlatformException catch (e, s) {
      print(e);
      print(s);
      return true;
    }
  });
}

void main() {
  runApp(MyApp());
  Workmanager().initialize(callbackDispatcher, isInDebugMode: true);
}

/// example widget for call log plugin
class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
 List<CallLogEntry> _callLogEntries = <CallLogEntry>[]; // 将 Iterable 改为 List
  List<flutter.SimInfo> _simInfoList = <flutter.SimInfo>[];

  // 用于监听新通话记录的订阅
  StreamSubscription<dynamic>? _newCallLogsSubscription;

  @override
  void initState() {
    super.initState();
    _getSimInfo(); // 初始化时获取 SIM 卡信息

    // 监听新通话记录
    _listenForNewCallLogs();

    // 首次加载通话记录
    _loadInitialCallLogs();
  }

  // 监听新通话记录
  void _listenForNewCallLogs() {
    _newCallLogsSubscription = CallLog.listenNewCallLogs().listen((CallLogEntry newCallLog) {
      // 处理新的通话记录，例如添加到 _callLogEntries 列表中，并更新 UI
      setState(() {
        _callLogEntries.add(newCallLog);
      });
    });
  }

  // 首次加载通话记录
  Future<void> _loadInitialCallLogs() async {
    final lastSyncTimestamp = await _getLastSyncTimestamp();
    final initialCallLogs = await CallLog.query(dateTimeFrom: DateTime.fromMillisecondsSinceEpoch(lastSyncTimestamp));
    setState(() {
      _callLogEntries = initialCallLogs.toList(); // 将 Iterable 转换为 List
    });

    // 首次加载后，将上次同步时间戳更新为当前时间
    await _saveLastSyncTimestamp(DateTime.now().millisecondsSinceEpoch);
  }

  // 获取上次同步时间戳
  Future<int> _getLastSyncTimestamp() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt('last_sync_timestamp') ?? 0;
  }

  // 保存上次同步时间戳
  Future<void> _saveLastSyncTimestamp(int timestamp) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('last_sync_timestamp', timestamp);
  }


  Future<void> _getSimInfo() async {
    final SimCardInfo simCardInfoPlugin = SimCardInfo();
    final List<flutter.SimInfo> simInfoList =
        await simCardInfoPlugin.getSimInfo() ?? <flutter.SimInfo>[];
    setState(() {
      _simInfoList = simInfoList;
    });
  }

  @override
  Widget build(BuildContext context) {
    const TextStyle mono = TextStyle(fontFamily: 'monospace');
    final List<Widget> children = <Widget>[];

    // 显示所有 SIM 卡信息
    final List<Widget> simInfoWidgets = _simInfoList
        .map((flutter.SimInfo simInfo) => Text(
            'SIM ${simInfo.slotIndex}: ${simInfo.displayName}: ${simInfo.countryIso}',
            style: mono))
        .toList();

    children.add(Column(
      children: simInfoWidgets,
      crossAxisAlignment: CrossAxisAlignment.start,
    ));

    // 显示通话记录和对应的 SIM 卡信息
    for (CallLogEntry entry in _callLogEntries) {
      children.add(
        Column(
          children: <Widget>[
            const Divider(),
            Text('NUMBER     : ${entry.number}', style: mono),
            Text('SIM NAME   : ${entry.simDisplayName}', style: mono),
            Text('Phoneaccount ID   : ${entry.phoneAccountId}', style: mono),
            Text('slot ID   : ${entry.simSlotIndex}', style: mono),
            // 其他信息...
          ],
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.start,
        ),
      );
    }

    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('call_log example')),
        body: SingleChildScrollView(
          child: Column(
            children: <Widget>[
              Center(
                child: Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: ElevatedButton(
                    onPressed: () async {
                      // 请求权限
                      PermissionStatus status =
                          await Permission.phone.request();

                      if (status.isGranted) {
                        // 权限已授予，继续获取通话记录
                        await _loadInitialCallLogs();
                      } else {
                        // 权限被拒绝，处理错误
                        // 例如，显示一个对话框提示用户授予权限
                      }
                    },
                    child: const Text('Get all'),
                  ),
                ),
              ),
              Center(
                child: Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: ElevatedButton(
                    onPressed: () {
                      Workmanager().registerOneOffTask(
                        DateTime.now().millisecondsSinceEpoch.toString(),
                        'simpleTask',
                        existingWorkPolicy: ExistingWorkPolicy.replace,
                      );
                    },
                    child: const Text('Get all in background'),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(8.0),
                child: Column(children: children),
              ),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    // 取消订阅
    _newCallLogsSubscription?.cancel();
    super.dispose();
  }
}