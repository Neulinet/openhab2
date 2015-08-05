// Generated by CoffeeScript 1.9.2
'use strict';
angular.module('ZooLib.controllers.room', []).controller('RoomController', function(itemRepository, $log, $scope, $stateParams, $filter, $state, $q,$rootScope,itemService) {

  $rootScope.leftSidebarOpen = false;
  $rootScope.isBlackout = false;
 
  $scope.allRoomDisplay = {};
  this.rooms = {};
  this.scenes = [];
  this.newSceneDefault = {
    name: ''
  };

  $rootScope.console = console;
  /*$rootScope.console.log = function(content){
    console.log(content);
  };*/

  $rootScope.toggleSidebar = function () {
    $rootScope.leftSidebarOpen = !$rootScope.leftSidebarOpen;
    $rootScope.isBlackout = !$rootScope.isBlackout;
  };

  $scope.updateItem = function (rooms,item,newState) {

    angular.forEach(rooms, function(value,key){
      angular.forEach(value.members, function(subValue, subKey){
        if (subValue.name == item){
          subValue.state = newState;
        }
      });
    });

  }

  this.applyScene = function(items){

    var rooms = this.rooms;
    console.log(items);
    items.forEach(function(value, key,items){
      //console.log('value',value);
      //console.log('key',key);
      
      $scope.updateItem(rooms,value.name,value.state);
      $rootScope.$broadcast(value.name,value.state);
      itemService.sendCommand({itemName: value.name},value.state);
    });
    
  }
  this.deleteScene = function(sceneName){

    itemRepository.deleteScene(sceneName);
    for (var i=0;i<this.scenes.length;i++){
      if (this.scenes[i].name == sceneName){
        this.scenes.splice(i,1);
      }
    }
  
  }

  this.allItems={};
  this.newScene = {};

  this.saveNewScene = function(closeCallback) {
    var items=[];

    // process scene name to valid input
    this.newScene.name = this.newScene.name.trim().toUpperCase().replace(/\s{1,}/g, "_");
    for (var i=0;i<this.scenes.length;i++){
      if (this.scenes[i].name == this.newScene.name){
        $scope.sceneError = "Scene " + this.newScene.name + " already exists.";
        //$log.error("Scene " + this.newScene.name + " already exists.");
        return;
      }
    }
    // check the room
    if ($state.params.room !='all'){
      items = this.rooms[$state.params.room].members;
    }else{ // all room
      var oneRoomActivate = false;
      $scope.tempRooms = this.rooms
      angular.forEach($scope.tempRooms, function(room, roomName){
        // if 
        if (room.display){
          oneRoomActivate = true;
          items= items.concat(room.members);
        }
      });
      if (!oneRoomActivate){
        $scope.sceneError = "No room is activated for this scene";
        return
      }else{
         $scope.sceneError = '';
      }
    }
    $log.debug("Saving scene for items:", items);
    return itemRepository.createNewScene(this.newScene.name, items).then((function(_this) {
      return function() {
        console.log(_this);
        _this.scenes.push({
          name: _this.newScene.name,
          data: angular.copy(items)
        });
        _this.newScene = angular.copy(_this.newSceneDefault);
        return closeCallback();
      };
    })(this));


    
  };
  this.refreshItems = function(force) {
    $log.debug('State params 1',$state.params);
    var roomsPromise, scenesPromise;
    roomsPromise = $q.defer();
    scenesPromise = $q.defer();
   
    itemRepository.getRooms(force).then((function(_this) {
      return function(rooms) {
        rooms['all'] = {'name':'all','label':'All','members':[]};
        $scope.thisRoom = rooms[$state.params.room];
        $log.debug('This inside after getting rooms',rooms);
   
        _this.rooms = $filter('orderBy')(rooms, 'label');
        
        return roomsPromise.resolve(rooms);
      };
    })(this));
    itemRepository.getScenes().then((function(_this) {
     
      $log.debug('this inside defered,getting scenes',_this);

      return function(scenes) {

        $log.debug("loaded scenes", scenes);
        _this.scenes = scenes;
        $scope.customScenes = _this.scenes;
        return scenesPromise.resolve(scenes);
      };
    })(this));
    return $q.all(roomsPromise, scenesPromise);
  };
  //console.log("All items: %s",JSON.stringify(this.allItems));
  $scope.changeRoomMobile = function(room){
    //console.log(room);
    $state.go("rooms.room", {
      active: true,
      room: room.name
    });
  }

  this.refreshItems().then((function(_this) {
    return function() {
      var firstRoom;
      console.log('State ',$state)
      if (!$stateParams.room && (_this.rooms != null)) {
        firstRoom = Object.keys(_this.rooms).sort()[0];
        $log.debug("Initial redirect to room " + firstRoom);
        $scope.thisRoom = Object.keys(_this.rooms).sort()[0];
        $state.go("rooms.room", {
          active: true,
          room: firstRoom
        });
      }
    };
  })(this));
});
