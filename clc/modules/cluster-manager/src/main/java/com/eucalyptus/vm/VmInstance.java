/*******************************************************************************
 *Copyright (c) 2009 Eucalyptus Systems, Inc.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, only version 3 of the License.
 * 
 * 
 * This file is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Please contact Eucalyptus Systems, Inc., 130 Castilian
 * Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 * if you need additional information or have any questions.
 * 
 * This file may incorporate work covered under the following copyright and
 * permission notice:
 * 
 * Software License Agreement (BSD License)
 * 
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use of this software in source and binary forms, with
 * or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 * THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 * LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 * SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 * IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 * BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 * THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 * OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 * WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 * ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.vm;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.EntityTransaction;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceException;
import javax.persistence.PreRemove;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.Entity;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import org.hibernate.criterion.Restrictions;
import com.eucalyptus.address.Address;
import com.eucalyptus.address.Addresses;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.cloud.CloudMetadata.VmInstanceMetadata;
import com.eucalyptus.cloud.ResourceToken;
import com.eucalyptus.cloud.UserMetadata;
import com.eucalyptus.cloud.run.Allocations.Allocation;
import com.eucalyptus.cloud.util.ResourceAllocationException;
import com.eucalyptus.cluster.Clusters;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.Partition;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.ServiceConfigurations;
import com.eucalyptus.component.id.ClusterController;
import com.eucalyptus.component.id.Dns;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionExecutionException;
import com.eucalyptus.entities.TransientEntityException;
import com.eucalyptus.event.EventFailedException;
import com.eucalyptus.event.ListenerRegistry;
import com.eucalyptus.images.Emis;
import com.eucalyptus.images.Emis.BootableSet;
import com.eucalyptus.images.MachineImageInfo;
import com.eucalyptus.keys.KeyPairs;
import com.eucalyptus.keys.SshKeyPair;
import com.eucalyptus.network.ExtantNetwork;
import com.eucalyptus.network.NetworkGroup;
import com.eucalyptus.network.PrivateNetworkIndex;
import com.eucalyptus.records.Logs;
import com.eucalyptus.reporting.event.InstanceEvent;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.FullName;
import com.eucalyptus.util.OwnerFullName;
import com.eucalyptus.util.TypeMapper;
import com.eucalyptus.vm.VmBundleTask.BundleState;
import com.eucalyptus.vm.VmInstance.VmState;
import com.eucalyptus.vm.VmInstances.Timeout;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import edu.ucsb.eucalyptus.cloud.VmInfo;
import edu.ucsb.eucalyptus.msgs.AttachedVolume;
import edu.ucsb.eucalyptus.msgs.InstanceBlockDeviceMapping;
import edu.ucsb.eucalyptus.msgs.RunningInstancesItemType;

@Entity
@javax.persistence.Entity
@PersistenceContext( name = "eucalyptus_cloud" )
@Table( name = "metadata_instances" )
@Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
public class VmInstance extends UserMetadata<VmState> implements VmInstanceMetadata {
  private static final long    serialVersionUID = 1L;
  
  @Transient
  private static Logger        LOG              = Logger.getLogger( VmInstance.class );
  @Transient
  public static String         DEFAULT_TYPE     = "m1.small";
  @Embedded
  private VmNetworkConfig      networkConfig;
  @Embedded
  private final VmId           vmId;
  @Embedded
  private final VmBootRecord   bootRecord;
  @Embedded
  private final VmUsageStats   usageStats;
  @Embedded
  private final VmLaunchRecord launchRecord;
  @Embedded
  private VmRuntimeState       runtimeState;
  @Embedded
  private VmVolumeState        transientVolumeState;
  @Embedded
  private VmVolumeState        persistentVolumeState;
  @Embedded
  private final VmPlacement    placement;
  
  @Column( name = "metadata_vm_private_networking" )
  private final Boolean        privateNetwork;
  @NotFound( action = NotFoundAction.IGNORE )
  @ManyToMany( cascade = { CascadeType.ALL }, fetch = FetchType.LAZY )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private Set<NetworkGroup>    networkGroups    = Sets.newHashSet( );
  
  @NotFound( action = NotFoundAction.IGNORE )
  @OneToOne( fetch = FetchType.EAGER, cascade = { CascadeType.ALL }, orphanRemoval = true, optional = true )
  @JoinColumn( name = "metadata_vm_network_index", nullable = true, insertable = true, updatable = true )
  @Cache( usage = CacheConcurrencyStrategy.TRANSACTIONAL )
  private PrivateNetworkIndex  networkIndex;
  
  @PreRemove
  void cleanUp( ) {
    if ( this.networkGroups != null ) {
      this.networkGroups.clear( );
      this.networkGroups = null;
    }
    try {
      if ( this.networkIndex != null ) {
        this.networkIndex.release( );
        this.networkIndex.teardown( );
        this.networkIndex = null;
      }
    } catch ( final ResourceAllocationException ex ) {
      LOG.error( ex, ex );
    }
  }
  
  public enum Filters implements Predicate<VmInstance> {
    BUNDLING {
      
      @Override
      public boolean apply( final VmInstance arg0 ) {
        return arg0.getRuntimeState( ).isBundling( );
      }
      
    };
  }
  
  public enum VmStateSet implements Predicate<VmInstance> {
    RUN( VmState.PENDING, VmState.RUNNING ),
    CHANGING( VmState.PENDING, VmState.STOPPING, VmState.SHUTTING_DOWN ) {
      
      @Override
      public boolean apply( final VmInstance arg0 ) {
        return super.apply( arg0 ) || !arg0.eachVolumeAttachment( new Predicate<AttachedVolume>( ) {
          @Override
          public boolean apply( final AttachedVolume arg0 ) {
            return !arg0.getStatus( ).endsWith( "ing" );
          }
        } );
      }
      
    },
    EXPECTING_TEARDOWN( VmState.STOPPING, VmState.SHUTTING_DOWN ),
    STOP( VmState.STOPPING, VmState.STOPPED ),
    TERM( VmState.SHUTTING_DOWN, VmState.TERMINATED ),
    NOT_RUNNING( VmState.STOPPING, VmState.STOPPED, VmState.SHUTTING_DOWN, VmState.TERMINATED ),
    DONE( VmState.TERMINATED, VmState.BURIED );
    
    private Set<VmState> states;
    
    VmStateSet( final VmState... states ) {
      this.states = Sets.newHashSet( states );
    }
    
    @Override
    public boolean apply( final VmInstance arg0 ) {
      return this.states.contains( arg0.getState( ) );
    }
    
    public boolean contains( final Object o ) {
      return this.states.contains( o );
    }
    
  }
  
  public enum VmState implements Predicate<VmInstance> {
    PENDING( 0 ),
    RUNNING( 16 ),
    SHUTTING_DOWN( 32 ),
    TERMINATED( 48 ),
    STOPPING( 64 ),
    STOPPED( 80 ),
    BURIED( 128 );
    private String name;
    private int    code;
    
    VmState( final int code ) {
      this.name = this.name( ).toLowerCase( ).replace( "_", "-" );
      this.code = code;
    }
    
    public String getName( ) {
      return this.name;
    }
    
    public int getCode( ) {
      return this.code;
    }
    
    public static class Mapper {
      private static Map<String, VmState> stateMap = getStateMap( );
      
      private static Map<String, VmState> getStateMap( ) {
        final Map<String, VmState> map = new HashMap<String, VmState>( );
        map.put( "Extant", VmState.RUNNING );
        map.put( "Pending", VmState.PENDING );
        map.put( "Teardown", VmState.SHUTTING_DOWN );
        return map;
      }
      
      public static VmState get( final String stateName ) {
        return Mapper.stateMap.get( stateName );
      }
    }
    
    @Override
    public boolean apply( final VmInstance arg0 ) {
      return this.equals( arg0.getState( ) );
    }
  }
  
  public enum RestoreAllocation implements Predicate<VmInfo> {
    INSTANCE;
    
    private static Function<String, NetworkGroup> transformNetworkNames( final UserFullName userFullName ) {
      return new Function<String, NetworkGroup>( ) {
        
        @Override
        public NetworkGroup apply( final String arg0 ) {
          final NetworkGroup result = ( NetworkGroup ) Entities.createCriteria( NetworkGroup.class ).setReadOnly( true )
                                                               .add( Restrictions.like( "naturalId", arg0.replace( userFullName.getAccountNumber( ) + "-", "" )
                                                                                                     + "%" ) )
                                                               .uniqueResult( );
          return result;
        }
      };
    }
    
    @Override
    public boolean apply( final VmInfo input ) {
      final EntityTransaction db = Entities.get( VmInstance.class );
      try {
        final VmType vmType = VmTypes.getVmType( input.getInstanceType( ).getName( ) );
        final UserFullName userFullName = UserFullName.getInstance( input.getOwnerId( ) );
        Partition partition;
        try {
          partition = Partitions.lookupByName( input.getPlacement( ) );
        } catch ( final Exception ex2 ) {
          partition = Partitions.lookupByName( Clusters.getInstance( ).lookup( input.getPlacement( ) ).getPartition( ) );
        }
        String imageId = null;
        String kernelId = null;
        String ramdiskId = null;
        try {
          imageId = input.getInstanceType( ).lookupRoot( ).getId( );
          kernelId = input.getInstanceType( ).lookupKernel( ).getId( );
          ramdiskId = input.getInstanceType( ).lookupRamdisk( ).getId( );
        } catch ( final Exception ex2 ) {
          LOG.error( ex2, ex2 );
        }
        BootableSet bootSet = null;
        if ( imageId != null ) {
          bootSet = Emis.newBootableSet( vmType, partition, imageId, kernelId, ramdiskId );
        } else {
          //TODO:GRZE: handle the case where an instance is running and it's root emi has been deregistered.
        }
        
        int launchIndex;
        try {
          launchIndex = Integer.parseInt( input.getLaunchIndex( ) );
        } catch ( final Exception ex1 ) {
          launchIndex = 1;
        }
        
        SshKeyPair keyPair = null;
        try {
          keyPair = KeyPairs.lookup( userFullName, input.getKeyValue( ) );
        } catch ( final Exception ex ) {
          keyPair = KeyPairs.noKey( );
        }
        
        byte[] userData = null;
        try {
          userData = Base64.decode( input.getUserData( ) );
        } catch ( final Exception ex ) {
          userData = new byte[0];
        }
        
        final List<NetworkGroup> networks = Lists.newArrayList( );
        try {
          networks.addAll( Lists.transform( input.getGroupNames( ), transformNetworkNames( userFullName ) ) );
        } catch ( final Exception ex ) {
          LOG.error( ex, ex );
        }
        
        PrivateNetworkIndex index = null;
        ExtantNetwork exNet;
        final NetworkGroup network = ( !networks.isEmpty( )
          ? networks.get( 0 )
          : null );
        if ( network != null ) {
          if ( !network.hasExtantNetwork( ) ) {
            exNet = network.reclaim( input.getNetParams( ).getVlan( ) );
          } else {
            exNet = network.extantNetwork( );
            if ( !exNet.getTag( ).equals( input.getNetParams( ).getVlan( ) ) ) {
              exNet = null;
            } else {
              index = exNet.reclaimNetworkIndex( input.getNetParams( ).getNetworkIndex( ) );
            }
          }
        }
        
        final VmInstance vmInst = new VmInstance.Builder( ).owner( userFullName )
                                                           .withIds( input.getInstanceId( ), input.getReservationId( ) )
                                                           .bootRecord( bootSet,
                                                                        userData,
                                                                        keyPair,
                                                                        vmType )
                                                           .placement( partition, partition.getName( ) )
                                                           .networking( networks, index )
                                                           .build( launchIndex );
        
        vmInst.setNaturalId( input.getUuid( ) );
        Address addr;
        try {
          addr = Addresses.getInstance( ).lookup( input.getNetParams( ).getIgnoredPublicIp( ) );
          if ( addr.isAssigned( ) &&
               addr.getInstanceAddress( ).equals( input.getNetParams( ).getIpAddress( ) ) &&
               addr.getInstanceId( ).equals( input.getInstanceId( ) ) ) {
            vmInst.updateAddresses( input.getNetParams( ).getIpAddress( ), input.getNetParams( ).getIgnoredPublicIp( ) );
          } else if ( !addr.isAssigned( ) && addr.isAllocated( ) && ( addr.isSystemOwned( ) || addr.getOwner( ).equals( userFullName ) ) ) {
            vmInst.updateAddresses( input.getNetParams( ).getIpAddress( ), input.getNetParams( ).getIgnoredPublicIp( ) );
          } else {
            vmInst.updateAddresses( input.getNetParams( ).getIpAddress( ), input.getNetParams( ).getIpAddress( ) );
          }
        } catch ( final Exception ex ) {
          LOG.error( ex );
        }
        Entities.persist( vmInst );
        db.commit( );
        return true;
      } catch ( final Exception ex ) {
        Logs.extreme( ).error( ex, ex );
        db.rollback( );
        return false;
      }
      //TODO:GRZE: this is the case in restore where we either need to report the failed instance restore, terminate the instance, or handle partial reporting of the instance info.
//      } catch ( NoSuchElementException e ) {
//        ClusterConfiguration config = Clusters.getInstance( ).lookup( runVm.getPlacement( ) ).getConfiguration( );
//        AsyncRequests.newRequest( new TerminateCallback( runVm.getInstanceId( ) ) ).dispatch( runVm.getPlacement( ) );
    }
    
  }
  
  enum Transitions implements Function<VmInstance, VmInstance> {
    REGISTER {
      @Override
      public VmInstance apply( final VmInstance arg0 ) {
        final EntityTransaction db = Entities.get( VmInstance.class );
        try {
          final VmInstance entityObj = Entities.merge( arg0 );
          db.commit( );
          return entityObj;
        } catch ( final RuntimeException ex ) {
          Logs.extreme( ).error( ex, ex );
          db.rollback( );
          throw ex;
        }
      }
    },
    START {
      @Override
      public VmInstance apply( final VmInstance v ) {
        if ( !Entities.isPersistent( v ) ) {
          throw new TransientEntityException( v.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            final VmInstance vm = Entities.merge( v );
            vm.setState( VmState.PENDING, Reason.USER_STARTED );
            db.commit( );
            return vm;
          } catch ( final RuntimeException ex ) {
            Logs.extreme( ).error( ex, ex );
            db.rollback( );
            throw ex;
          }
        }
      }
    },
    TERMINATED {
      @Override
      public VmInstance apply( final VmInstance v ) {
        if ( !Entities.isPersistent( v ) ) {
          throw new TransientEntityException( v.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            final VmInstance vm = Entities.merge( v );
            if ( VmStateSet.RUN.apply( vm ) ) {
              vm.setState( VmState.SHUTTING_DOWN, ( Timeout.SHUTTING_DOWN.apply( vm )
                ? Reason.EXPIRED
                : Reason.USER_TERMINATED ) );
            } else if ( VmState.SHUTTING_DOWN.equals( vm.getState( ) ) ) {
              vm.setState( VmState.TERMINATED, Timeout.TERMINATED.apply( vm )
                ? Reason.EXPIRED
                : Reason.USER_TERMINATED );
            }
            db.commit( );
            return vm;
          } catch ( final Exception ex ) {
            Logs.exhaust( ).trace( ex, ex );
            db.rollback( );
            throw new NoSuchElementException( "Failed to lookup instance: " + v );
          }
        }
      }
    },
    STOPPED {
      @Override
      public VmInstance apply( final VmInstance v ) {
        if ( !Entities.isPersistent( v ) ) {
          throw new TransientEntityException( v.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            final VmInstance vm = Entities.merge( v );
            if ( VmStateSet.RUN.apply( vm ) ) {
              vm.setState( VmState.STOPPING, Reason.USER_STOPPED );
            } else if ( VmState.STOPPING.equals( vm.getState( ) ) ) {
              vm.setState( VmState.STOPPED, Reason.USER_STOPPED );
            }
            db.commit( );
            return vm;
          } catch ( final Exception ex ) {
            Logs.exhaust( ).trace( ex, ex );
            db.rollback( );
            throw new NoSuchElementException( "Failed to lookup instance: " + v );
          }
        }
      }
    },
    DELETE {
      @Override
      public VmInstance apply( final VmInstance vm ) {
        if ( !Entities.isPersistent( vm ) ) {
          throw new TransientEntityException( vm.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            vm.cleanUp( );
            vm.setState( VmState.BURIED );
            Entities.delete( vm );
            db.commit( );
            return vm;
          } catch ( final Exception ex ) {
            Logs.exhaust( ).trace( ex, ex );
            db.rollback( );
            throw new NoSuchElementException( "Failed to lookup instance: " + vm );
          }
        }
      }
    },
    SHUTDOWN {
      @Override
      public VmInstance apply( final VmInstance v ) {
        if ( !Entities.isPersistent( v ) ) {
          throw new TransientEntityException( v.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            final VmInstance vm = Entities.merge( v );
            if ( VmStateSet.RUN.apply( vm ) ) {
              vm.setState( VmState.SHUTTING_DOWN, ( Timeout.SHUTTING_DOWN.apply( vm )
                ? Reason.EXPIRED
                : Reason.USER_TERMINATED ) );
            }
            db.commit( );
            return vm;
          } catch ( final Exception ex ) {
            Logs.exhaust( ).trace( ex, ex );
            db.rollback( );
            throw new NoSuchElementException( "Failed to lookup instance: " + v );
          }
        }
      }
    };
    public abstract VmInstance apply( final VmInstance v );
  }
  
  public enum Lookup implements Function<String, VmInstance> {
    INSTANCE {
      
      @Override
      public VmInstance apply( final String arg0 ) {
        final EntityTransaction db = Entities.get( VmInstance.class );
        try {
          final VmInstance vm = Entities.uniqueResult( VmInstance.named( null, arg0 ) );
          if ( ( vm == null ) || VmStateSet.DONE.apply( vm ) ) {
            throw new NoSuchElementException( "Failed to lookup vm instance: " + arg0 + "\nFound: " + vm );
          }
          db.commit( );
          return vm;
        } catch ( final NoSuchElementException ex ) {
          db.rollback( );
          throw ex;
        } catch ( final Exception ex ) {
          db.rollback( );
          throw new NoSuchElementException( "Failed to lookup vm instance: " + arg0 );
        }
      }
    },
    TERMINATED {
      @Override
      public VmInstance apply( final String arg0 ) {
        final EntityTransaction db = Entities.get( VmInstance.class );
        try {
          final VmInstance vm = Entities.uniqueResult( VmInstance.named( null, arg0 ) );
          if ( ( vm == null ) || !VmStateSet.DONE.apply( vm ) ) {
            throw new NoSuchElementException( "Failed to lookup vm instance: " + arg0 );
          }
          db.commit( );
          return vm;
        } catch ( final NoSuchElementException ex ) {
          db.rollback( );
          throw ex;
        } catch ( final Exception ex ) {
          db.rollback( );
          throw new NoSuchElementException( "Failed to lookup vm instance: " + arg0 );
        }
      }
    };
    public abstract VmInstance apply( final String arg0 );
  }
  
  public enum FilterTerminated implements Predicate<VmInstance> {
    INSTANCE;
    
    @Override
    public boolean apply( final VmInstance arg0 ) {
      return !VmStateSet.DONE.apply( arg0 );
    }
  }
  
  public enum Create implements Function<ResourceToken, VmInstance> {
    INSTANCE;
    
    /**
     * @see com.google.common.base.Predicate#apply(java.lang.Object)
     */
    @Override
    public VmInstance apply( final ResourceToken token ) {
      final EntityTransaction db = Entities.get( VmInstance.class );
      try {
        final Allocation allocInfo = token.getAllocationInfo( );
        VmInstance vmInst = new VmInstance.Builder( ).owner( allocInfo.getOwnerFullName( ) )
                                                     .withIds( token.getInstanceId( ), allocInfo.getReservationId( ) )
                                                     .bootRecord( allocInfo.getBootSet( ),
                                                                  allocInfo.getUserData( ),
                                                                  allocInfo.getSshKeyPair( ),
                                                                  allocInfo.getVmType( ) )
                                                     .placement( allocInfo.getPartition( ), allocInfo.getRequest( ).getAvailabilityZone( ) )
                                                     .networking( allocInfo.getNetworkGroups( ), token.getNetworkIndex( ) )
                                                     .build( token.getLaunchIndex( ) );
        vmInst = Entities.persist( vmInst );
        Entities.flush( vmInst );
        db.commit( );
        token.setVmInstance( vmInst );
        return vmInst;
      } catch ( final ResourceAllocationException ex ) {
        db.rollback( );
        Logs.extreme( ).error( ex, ex );
        throw Exceptions.toUndeclared( ex );
      } catch ( final Exception ex ) {
        db.rollback( );
        Logs.extreme( ).error( ex, ex );
        throw Exceptions.toUndeclared( new TransactionExecutionException( ex ) );
      }
    }
    
  }
  
  public static class Builder {
    VmId                vmId;
    VmBootRecord        vmBootRecord;
    VmUsageStats        vmUsageStats;
    VmPlacement         vmPlacement;
    VmLaunchRecord      vmLaunchRecord;
    List<NetworkGroup>  networkRulesGroups;
    PrivateNetworkIndex networkIndex;
    OwnerFullName       owner;
    
    public Builder owner( final OwnerFullName owner ) {
      this.owner = owner;
      return this;
    }
    
    public Builder networking( final List<NetworkGroup> groups, final PrivateNetworkIndex networkIndex ) {
      this.networkRulesGroups = groups;
      this.networkIndex = networkIndex;
      return this;
    }
    
    public Builder withIds( final String instanceId, final String reservationId ) {
      this.vmId = new VmId( reservationId, instanceId );
      return this;
    }
    
    public Builder placement( final Partition partition, final String clusterName ) {
      final ServiceConfiguration config = Partitions.lookupService( ClusterController.class, partition );
      this.vmPlacement = new VmPlacement( config.getName( ), config.getPartition( ) );
      return this;
    }
    
    public Builder bootRecord( final BootableSet bootSet, final byte[] userData, final SshKeyPair sshKeyPair, final VmType vmType ) {
      this.vmBootRecord = new VmBootRecord( bootSet, userData, sshKeyPair, vmType );
      return this;
    }
    
    private ServiceConfiguration lookupServiceConfiguration( final String name ) {
      ServiceConfiguration config = null;
      try {
        config = ServiceConfigurations.lookupByName( ClusterController.class, name );
      } catch ( final PersistenceException ex ) {
        LOG.debug( "Failed to find cluster configuration named: " + name + " using that as the partition name." );
      }
      return config;
    }
    
    public VmInstance build( final Integer launchndex ) throws ResourceAllocationException {
      return new VmInstance( this.owner, this.vmId, this.vmBootRecord, new VmLaunchRecord( launchndex, new Date( ) ), this.vmPlacement,
                             this.networkRulesGroups, this.networkIndex );
    }
  }
  
  private VmInstance( final OwnerFullName owner,
                      final VmId vmId,
                      final VmBootRecord bootRecord,
                      final VmLaunchRecord launchRecord,
                      final VmPlacement placement,
                      final List<NetworkGroup> networkRulesGroups,
                      final PrivateNetworkIndex networkIndex ) throws ResourceAllocationException {
    super( owner, vmId.getInstanceId( ) );
    this.setState( VmState.PENDING );
    this.vmId = vmId;
    this.bootRecord = bootRecord;
    this.launchRecord = launchRecord;
    this.placement = placement;
    this.privateNetwork = Boolean.FALSE;
    this.usageStats = new VmUsageStats( this );
    this.runtimeState = new VmRuntimeState( this );
    this.transientVolumeState = new VmVolumeState( this );
    this.persistentVolumeState = new VmVolumeState( this );
    this.networkConfig = new VmNetworkConfig( this );
    final Function<NetworkGroup, NetworkGroup> func = Entities.merge( );
    this.networkGroups.addAll( Collections2.transform( networkRulesGroups, func ) );
    this.networkIndex = networkIndex != PrivateNetworkIndex.bogus( )
      ? Entities.merge( networkIndex.set( this ) )
      : null;
    this.store( );
  }
  
  protected VmInstance( final OwnerFullName ownerFullName, final String instanceId2 ) {
    super( ownerFullName, instanceId2 );
    this.runtimeState = null;
    this.vmId = null;
    this.bootRecord = null;
    this.launchRecord = null;
    this.placement = null;
    this.privateNetwork = null;
    this.usageStats = null;
    this.networkConfig = null;
    this.transientVolumeState = null;
    this.persistentVolumeState = null;
  }
  
  protected VmInstance( ) {
    this.vmId = null;
    this.bootRecord = null;
    this.launchRecord = null;
    this.placement = null;
    this.privateNetwork = null;
    this.networkIndex = null;
    this.usageStats = null;
    this.runtimeState = null;
    this.networkConfig = null;
    this.transientVolumeState = null;
    this.persistentVolumeState = null;
  }
  
  public void updateBlockBytes( final long blkbytes ) {
    this.usageStats.setBlockBytes( this.usageStats.getBlockBytes( ) + blkbytes );
  }
  
  public void updateNetworkBytes( final long netbytes ) {
    this.usageStats.setNetworkBytes( this.usageStats.getNetworkBytes( ) + netbytes );
  }
  
  public void updateAddresses( final String privateAddr, final String publicAddr ) {
    this.updatePrivateAddress( privateAddr );
    this.updatePublicAddress( publicAddr );
  }
  
  public void updatePublicAddress( final String publicAddr ) {
    if ( !VmNetworkConfig.DEFAULT_IP.equals( publicAddr ) && !"".equals( publicAddr )
         && ( publicAddr != null ) ) {
      this.getNetworkConfig( ).setPublicAddress( publicAddr );
    } else {
      this.getNetworkConfig( ).setPublicAddress( VmNetworkConfig.DEFAULT_IP );
    }
  }
  
  public void updatePrivateAddress( final String privateAddr ) {
    if ( !VmNetworkConfig.DEFAULT_IP.equals( privateAddr ) && !"".equals( privateAddr ) && ( privateAddr != null ) ) {
      this.getNetworkConfig( ).setPrivateAddress( privateAddr );
    }
    this.getNetworkConfig( ).updateDns( );
  }
  
  public VmRuntimeState getRuntimeState( ) {
    if ( this.runtimeState == null ) {
      this.runtimeState = new VmRuntimeState( this );
    }
    return this.runtimeState;
  }
  
  private void setRuntimeState( final VmState state ) {
    this.setState( state, Reason.NORMAL );
  }
  
  void store( ) {
    this.fireUsageEvent( );
    this.firePersist( );
  }
  
  private void firePersist( ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    if ( !Entities.isPersistent( this ) ) {
      db.rollback( );
    } else {
      try {
        Entities.merge( this );
        db.commit( );
      } catch ( final Exception ex ) {
        db.rollback( );
        LOG.debug( ex );
      }
    }
  }
  
  private void fireUsageEvent( ) {
    try {
      ListenerRegistry.getInstance( ).fireEvent( new InstanceEvent( this.getInstanceUuid( ), this.getDisplayName( ),
                                                                    this.bootRecord.getVmType( ).getName( ),
                                                                    this.getOwner( ).getUserId( ), this.getOwnerUserName( ),
                                                                    this.getOwner( ).getAccountNumber( ), this.getOwnerAccountName( ),
                                                                    this.placement.getClusterName( ), this.placement.getPartitionName( ),
                                                                    this.usageStats.getNetworkBytes( ), this.usageStats.getBlockBytes( ) ) );
    } catch ( final EventFailedException ex ) {
      LOG.error( ex, ex );
    }
  }
  
  public String getByKey( final String pathArg ) {
    final Map<String, String> m = this.getMetadataMap( );
    String path = ( pathArg != null )
      ? pathArg
      : "";
    LOG.debug( "Servicing metadata request:" + path + " -> " + m.get( path ) );
    if ( m.containsKey( path + "/" ) ) path += "/";
    return m.get( path ).replaceAll( "\n*\\z", "" );
  }
  
  private Map<String, String> getMetadataMap( ) {
    final boolean dns = !ComponentIds.lookup( Dns.class ).runLimitedServices( );
    final Map<String, String> m = new HashMap<String, String>( );
    m.put( "ami-id", this.getImageId( ) );
    m.put( "product-codes", this.bootRecord.getMachine( ).getProductCodes( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    m.put( "ami-launch-index", "" + this.launchRecord.getLaunchIndex( ) );
//ASAP: FIXME: GRZE:
//    m.put( "ancestor-ami-ids", this.getImageInfo( ).getAncestorIds( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    if ( this.bootRecord.getMachine( ) instanceof MachineImageInfo ) {
      m.put( "ami-manifest-path", ( ( MachineImageInfo ) this.bootRecord.getMachine( ) ).getManifestLocation( ) );
    }
    m.put( "hostname", this.getPublicAddress( ) );
    m.put( "instance-id", this.getInstanceId( ) );
    m.put( "instance-type", this.getVmType( ).getName( ) );
    if ( dns ) {
      m.put( "local-hostname", this.getNetworkConfig( ).getPrivateDnsName( ) );
    } else {
      m.put( "local-hostname", this.getNetworkConfig( ).getPrivateAddress( ) );
    }
    m.put( "local-ipv4", this.getNetworkConfig( ).getPrivateAddress( ) );
    if ( dns ) {
      m.put( "public-hostname", this.getNetworkConfig( ).getPublicDnsName( ) );
    } else {
      m.put( "public-hostname", this.getPublicAddress( ) );
    }
    m.put( "public-ipv4", this.getPublicAddress( ) );
    m.put( "reservation-id", this.vmId.getReservationId( ) );
    m.put( "kernel-id", this.bootRecord.getKernel( ).getDisplayName( ) );
    if ( this.bootRecord.getRamdisk( ) != null ) {
      m.put( "ramdisk-id", this.bootRecord.getRamdisk( ).getDisplayName( ) );
    }
    m.put( "security-groups", this.getNetworkNames( ).toString( ).replaceAll( "[\\Q[]\\E]", "" ).replaceAll( ", ", "\n" ) );
    
    m.put( "block-device-mapping/", "emi\nephemeral\nephemeral0\nroot\nswap" );
    m.put( "block-device-mapping/emi", "sda1" );
    m.put( "block-device-mapping/ami", "sda1" );
    m.put( "block-device-mapping/ephemeral", "sda2" );
    m.put( "block-device-mapping/ephemeral0", "sda2" );
    m.put( "block-device-mapping/swap", "sda3" );
    m.put( "block-device-mapping/root", "/dev/sda1" );
    
    m.put( "public-keys/", "0=" + this.bootRecord.getSshKeyPair( ).getName( ) );
    m.put( "public-keys/0", "openssh-key" );
    m.put( "public-keys/0/", "openssh-key" );
    m.put( "public-keys/0/openssh-key", this.bootRecord.getSshKeyPair( ).getPublicKey( ) );
    
    m.put( "placement/", "availability-zone" );
    m.put( "placement/availability-zone", this.getPartition( ) );
    String dir = "";
    for ( final String entry : m.keySet( ) ) {
      if ( ( entry.contains( "/" ) && !entry.endsWith( "/" ) ) ) {
//          || ( "ramdisk-id".equals(entry) && this.getImageInfo( ).getRamdiskId( ) == null ) ) {
        continue;
      }
      dir += entry + "\n";
    }
    m.put( "", dir );
    return m;
  }
  
  public synchronized long getSplitTime( ) {
    final long time = System.currentTimeMillis( );
    final long split = time - super.getLastUpdateTimestamp( ).getTime( );
    return split;
  }
  
  public VmBundleTask resetBundleTask( ) {
    return this.getRuntimeState( ).resetBundleTask( );
  }
  
  BundleState getBundleTaskState( ) {
    return this.getRuntimeState( ).getBundleTask( ).getState( );
  }
  
  public String getImageId( ) {
    return this.bootRecord.getMachine( ).getDisplayName( );
  }
  
  public boolean hasPublicAddress( ) {
    return ( this.networkConfig != null )
           && !( VmNetworkConfig.DEFAULT_IP.equals( this.getNetworkConfig( ).getPublicAddress( ) ) || this.getNetworkConfig( ).getPrivateAddress( ).equals( this.getNetworkConfig( ).getPublicAddress( ) ) );
  }
  
  public String getInstanceId( ) {
    return super.getDisplayName( );
  }
  
  public String getConsoleOutputString( ) {
    return new String( Base64.encode( this.getRuntimeState( ).getConsoleOutput( ).toString( ).getBytes( ) ) );
  }
  
  public void setConsoleOutput( final StringBuffer consoleOutput ) {
    this.getRuntimeState( ).setConsoleOutput( consoleOutput );
  }
  
  public VmType getVmType( ) {
    return this.bootRecord.getVmType( );
  }
  
  public NavigableSet<String> getNetworkNames( ) {
    return new TreeSet<String>( Collections2.transform( this.getNetworkGroups( ), new Function<NetworkGroup, String>( ) {
      
      @Override
      public String apply( final NetworkGroup arg0 ) {
        return arg0.getDisplayName( );
      }
    } ) );
  }
  
  public String getPrivateAddress( ) {
    return this.getNetworkConfig( ).getPrivateAddress( );
  }
  
  public String getPublicAddress( ) {
    return this.getNetworkConfig( ).getPublicAddress( );
  }
  
  public String getPrivateDnsName( ) {
    return this.getNetworkConfig( ).getPrivateDnsName( );
  }
  
  public String getPublicDnsName( ) {
    return this.getNetworkConfig( ).getPublicDnsName( );
  }
  
  public String getPasswordData( ) {
    return this.getRuntimeState( ).getPasswordData( );
  }
  
  public void setPasswordData( final String passwordData ) {
    this.getRuntimeState( ).setPasswordData( passwordData );
  }
  
  /**
   * @return the platform
   */
  public String getPlatform( ) {
    return this.bootRecord.getPlatform( );
  }
  
  /**
   * @return the bundleTask
   */
  public BundleTask getBundleTask( ) {
    return VmBundleTask.asBundleTask( ).apply( this.getRuntimeState( ).getBundleTask( ) );
  }
  
  /**
   * @return the networkBytes
   */
  public Long getNetworkBytes( ) {
    return this.usageStats.getNetworkBytes( );
  }
  
  /**
   * @return the blockBytes
   */
  public Long getBlockBytes( ) {
    return this.usageStats.getBlockBytes( );
  }
  
  @Override
  public String getPartition( ) {
    return this.placement.getPartitionName( );
  }
  
  public String getInstanceUuid( ) {
    return this.getNaturalId( );
  }
  
  public static VmInstance named( final OwnerFullName ownerFullName, final String instanceId ) {
    return new VmInstance( ownerFullName, instanceId );
  }
  
  public static VmInstance namedTerminated( final OwnerFullName ownerFullName, final String instanceId ) {
    return new VmInstance( ownerFullName, instanceId ) {
      /**
       * 
       */
      private static final long serialVersionUID = 1L;
      
      {
        this.setState( VmState.TERMINATED );
      }
    };
  }
  
  @Override
  public FullName getFullName( ) {
    return FullName.create.vendor( "euca" )
                          .region( ComponentIds.lookup( Eucalyptus.class ).name( ) )
                          .namespace( this.getOwnerAccountNumber( ) )
                          .relativeId( "instance", this.getDisplayName( ) );
  }
  
  public enum Reason {
    NORMAL( "" ),
    EXPIRED( "Instance expired after not being reported for %s mins.", VmInstances.Timeout.UNREPORTED.getMinutes( ) ),
    FAILED( "The instance failed to start on the NC." ),
    USER_TERMINATED( "User terminated." ),
    USER_STOPPED( "User stopped." ),
    USER_STARTED( "User started." ),
    APPEND( "" );
    private String   message;
    private Object[] args;
    
    Reason( final String message, final Object... args ) {
      this.message = message;
      this.args = args;
    }
    
    @Override
    public String toString( ) {
      return String.format( this.message.toString( ), this.args );
    }
    
  }
  
  private PrivateNetworkIndex getNetworkIndex( ) {
    return this.networkIndex;
  }
  
  public void releaseNetworkIndex( ) {
    try {
      this.networkIndex.release( );
      
    } catch ( final ResourceAllocationException ex ) {
      LOG.trace( ex, ex );
      LOG.error( ex );
    }
  }
  
  private Boolean getPrivateNetwork( ) {
    return this.privateNetwork;
  }
  
  public Set<NetworkGroup> getNetworkGroups( ) {
    return ( Set<NetworkGroup> ) ( this.networkGroups != null
      ? this.networkGroups
      : Sets.newHashSet( ) );
  }
  
  static long getSerialversionuid( ) {
    return serialVersionUID;
  }
  
  static Logger getLOG( ) {
    return LOG;
  }
  
  static String getDEFAULT_IP( ) {
    return VmNetworkConfig.DEFAULT_IP;
  }
  
  static String getDEFAULT_TYPE( ) {
    return DEFAULT_TYPE;
  }
  
  VmId getVmId( ) {
    return this.vmId;
  }
  
  VmBootRecord getBootRecord( ) {
    return this.bootRecord;
  }
  
  VmUsageStats getUsageStats( ) {
    return this.usageStats;
  }
  
  VmLaunchRecord getLaunchRecord( ) {
    return this.launchRecord;
  }
  
  VmPlacement getPlacement( ) {
    return this.placement;
  }
  
  public Partition lookupPartition( ) {
    return this.placement.lookupPartition( );
  }
  
  /**
   * @param stopping
   * @param reason
   */
  public void setState( final VmState stopping, final Reason reason, final String... extra ) {
    
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      entity.runtimeState.setState( stopping, reason, extra );
      if ( VmStateSet.DONE.apply( entity ) ) {
        entity.cleanUp( );
      }
      db.commit( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      throw new RuntimeException( ex );
    }
  }
  
  /**
   * @param predicate
   */
  public void lookupVolumeAttachmentByDevice( final String volumeDevice ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      this.transientVolumeState.lookupVolumeAttachmentByDevice( volumeDevice );
      db.commit( );
    } catch ( final NoSuchElementException ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      throw ex;
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      throw new NoSuchElementException( "Failed to lookup volume with device: " + volumeDevice );
    }
  }
  
  /**
   * @param volumeId
   * @return
   */
  public AttachedVolume lookupVolumeAttachment( final String volumeId ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      final AttachedVolume ret = VmVolumeAttachment.asAttachedVolume( entity ).apply( this.transientVolumeState.lookupVolumeAttachment( volumeId ) );
      db.commit( );
      return ret;
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      throw new NoSuchElementException( "Failed to lookup volume: " + volumeId );
    }
  }
  
  /**
   * @param attachVol
   */
  public void addVolumeAttachment( final AttachedVolume vol ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      this.transientVolumeState.addVolumeAttachment( VmVolumeAttachment.fromAttachedVolume( entity ).apply( vol ) );
      db.commit( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
    }
    
  }
  
  /**
   * @return
   */
  public ServiceConfiguration lookupClusterConfiguration( ) {
    return Partitions.lookupService( ClusterController.class, this.lookupPartition( ) );
  }
  
  /**
   * @param predicate
   * @return
   */
  public boolean eachVolumeAttachment( final Predicate<AttachedVolume> predicate ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      final boolean ret = this.transientVolumeState.eachVolumeAttachment( new Predicate<VmVolumeAttachment>( ) {
        
        @Override
        public boolean apply( final VmVolumeAttachment arg0 ) {
          return predicate.apply( VmVolumeAttachment.asAttachedVolume( VmInstance.this ).apply( arg0 ) );
        }
      } );
      db.commit( );
      return ret;
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      return false;
    }
    
  }
  
  /**
   * @param volumeId
   * @return
   */
  public AttachedVolume removeVolumeAttachment( final String volumeId ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      final AttachedVolume ret = VmVolumeAttachment.asAttachedVolume( entity ).apply( this.transientVolumeState.removeVolumeAttachment( volumeId ) );
      db.commit( );
      return ret;
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
      throw new NoSuchElementException( "Failed to lookup volume: " + volumeId );
    }
  }
  
  /**
   * @return
   */
  public String getServiceTag( ) {
    return this.getRuntimeState( ).getServiceTag( );
  }
  
  /**
   * @return
   */
  public String getReservationId( ) {
    return this.vmId.getReservationId( );
  }
  
  /**
   * @return
   */
  public byte[] getUserData( ) {
    return this.bootRecord.getUserData( );
  }
  
  public void clearPendingBundleTask( ) {
    this.getRuntimeState( ).clearPendingBundleTask( );
  }
  
  /**
   * @param volumeId
   * @param newState
   */
  public void updateVolumeAttachment( final String volumeId, final String newState ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      entity.getTransientVolumeState( ).updateVolumeAttachment( volumeId, newState );
      db.commit( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
    }
  }
  
  /**
   * @return
   */
  public Predicate<VmInfo> doUpdate( ) {
    return new Predicate<VmInfo>( ) {
      
      @Override
      public boolean apply( final VmInfo runVm ) {
        if ( !Entities.isPersistent( VmInstance.this ) ) {
          throw new TransientEntityException( this.toString( ) );
        } else {
          final EntityTransaction db = Entities.get( VmInstance.class );
          try {
            final VmState state = VmState.Mapper.get( runVm.getStateName( ) );
            if ( VmStateSet.RUN.apply( VmInstance.this ) && VmStateSet.RUN.contains( state ) ) {
              VmInstance.this.setState( state, Reason.APPEND, "UPDATE" );
              this.updateState( runVm );
            } else if ( VmState.STOPPING.apply( VmInstance.this ) && VmState.SHUTTING_DOWN.equals( state ) ) {
              VmInstance.this.setState( VmState.STOPPED, Reason.APPEND, "STOPPED" );
            } else if ( VmState.SHUTTING_DOWN.apply( VmInstance.this ) && state.equals( VmInstance.this.getState( ) ) ) {
              VmInstance.this.setState( VmState.TERMINATED, Reason.APPEND, "DONE" );
            } else if ( VmStateSet.STOP.apply( VmInstance.this ) && VmInstances.Timeout.SHUTTING_DOWN.apply( VmInstance.this ) ) {
              VmInstance.this.setState( VmState.STOPPED, Reason.EXPIRED );
            } else {
              this.updateState( runVm );
            }
            db.commit( );
          } catch ( final Exception ex ) {
            Logs.exhaust( ).error( ex, ex );
            db.rollback( );
          }
        }
        return true;
      }
      
      private void updateState( final VmInfo runVm ) {
        VmInstance.this.getRuntimeState( ).setServiceTag( runVm.getServiceTag( ) );
        VmInstance.this.getRuntimeState( ).updateBundleTaskState( runVm.getBundleTaskStateName( ) );
        VmInstance.this.updateCreateImageTaskState( runVm.getBundleTaskStateName( ) );
        VmInstance.this.updateVolumeAttachments( runVm.getVolumes( ) );
        VmInstance.this.updateAddresses( runVm.getNetParams( ).getIpAddress( ), runVm.getNetParams( ).getIgnoredPublicIp( ) );
        VmInstance.this.updateBlockBytes( runVm.getBlockBytes( ) );
        VmInstance.this.updateNetworkBytes( runVm.getNetworkBytes( ) );
      }
    };
  }
  
  /**
   * @param bundleTaskStateName
   */
  protected void updateCreateImageTaskState( final String createImageTaskStateName ) {
    this.getRuntimeState( ).setCreateImageTaskState( createImageTaskStateName );
  }
  
  /**
   * @param volumes
   */
  public void updateVolumeAttachments( final List<AttachedVolume> volumes ) {
    final EntityTransaction db = Entities.get( VmInstance.class );
    try {
      final VmInstance entity = Entities.merge( this );
      entity.getTransientVolumeState( ).updateVolumeAttachments( Lists.transform( volumes, VmVolumeAttachment.fromAttachedVolume( entity ) ) );
      db.commit( );
    } catch ( final Exception ex ) {
      Logs.exhaust( ).error( ex, ex );
      db.rollback( );
    }
  }
  
  /**
   * @param serviceTag
   */
  public void setServiceTag( final String serviceTag ) {
    this.getRuntimeState( ).setServiceTag( serviceTag );
  }
  
  /**
   * @param bundleTask
   * @return
   */
  public boolean startBundleTask( final BundleTask bundleTask ) {
    return this.getRuntimeState( ).startBundleTask( VmBundleTask.fromBundleTask( this ).apply( bundleTask ) );
  }
  
  public void setNetworkIndex( final PrivateNetworkIndex networkIndex ) {
    this.networkIndex = networkIndex;
  }
  
  @TypeMapper
  public enum Transform implements Function<VmInstance, RunningInstancesItemType> {
    INSTANCE;
    
    /**
     * @see com.google.common.base.Supplier#get()
     */
    @Override
    public RunningInstancesItemType apply( final VmInstance input ) {
      RunningInstancesItemType runningInstance;
      try {
        final boolean dns = !ComponentIds.lookup( Dns.class ).runLimitedServices( );
        runningInstance = new RunningInstancesItemType( );
        
        runningInstance.setAmiLaunchIndex( Integer.toString( input.getLaunchRecord( ).getLaunchIndex( ) ) );
        if ( ( input.getBundleTaskState( ) != null ) && !BundleState.none.equals( input.getBundleTaskState( ) ) ) {
          runningInstance.setStateCode( Integer.toString( VmState.TERMINATED.getCode( ) ) );
          runningInstance.setStateName( VmState.TERMINATED.getName( ) );
        } else {
          runningInstance.setStateCode( Integer.toString( input.getState( ).getCode( ) ) );
          runningInstance.setStateName( input.getState( ).getName( ) );
        }
        runningInstance.setPlatform( input.getPlatform( ) );
        
        runningInstance.setStateCode( Integer.toString( input.getState( ).getCode( ) ) );
        runningInstance.setStateName( input.getState( ).getName( ) );
        runningInstance.setInstanceId( input.getVmId( ).getInstanceId( ) );
        //ASAP:FIXME:GRZE: restore.
        runningInstance.setProductCodes( new ArrayList<String>( ) );
        runningInstance.setImageId( input.getBootRecord( ).getMachine( ).getDisplayName( ) );
        if ( input.getBootRecord( ).getKernel( ) != null ) {
          runningInstance.setKernel( input.getBootRecord( ).getKernel( ).getDisplayName( ) );
        }
        if ( input.getBootRecord( ).getRamdisk( ) != null ) {
          runningInstance.setRamdisk( input.getBootRecord( ).getRamdisk( ).getDisplayName( ) );
        }
        if ( dns ) {
          String publicDnsName = input.getPublicDnsName( );
          String privateDnsName = input.getPrivateDnsName( );
          publicDnsName = ( publicDnsName == null
            ? VmNetworkConfig.DEFAULT_IP
            : publicDnsName );
          privateDnsName = ( privateDnsName == null
            ? VmNetworkConfig.DEFAULT_IP
            : privateDnsName );
          runningInstance.setDnsName( publicDnsName );
          runningInstance.setIpAddress( publicDnsName );
          runningInstance.setPrivateDnsName( privateDnsName );
          runningInstance.setPrivateIpAddress( privateDnsName );
        } else {
          String publicDnsName = input.getPublicAddress( );
          String privateDnsName = input.getPrivateAddress( );
          publicDnsName = ( publicDnsName == null
            ? VmNetworkConfig.DEFAULT_IP
            : publicDnsName );
          privateDnsName = ( privateDnsName == null
            ? VmNetworkConfig.DEFAULT_IP
            : privateDnsName );
          runningInstance.setPrivateDnsName( privateDnsName );
          runningInstance.setPrivateIpAddress( privateDnsName );
          if ( !VmNetworkConfig.DEFAULT_IP.equals( publicDnsName ) ) {
            runningInstance.setDnsName( publicDnsName );
            runningInstance.setIpAddress( publicDnsName );
          } else {
            runningInstance.setDnsName( privateDnsName );
            runningInstance.setIpAddress( privateDnsName );
          }
        }
        
        runningInstance.setReason( input.runtimeState.getReason( ) );
        
        if ( input.getBootRecord( ).getSshKeyPair( ) != null )
          runningInstance.setKeyName( input.getBootRecord( ).getSshKeyPair( ).getName( ) );
        else runningInstance.setKeyName( "" );
        
        runningInstance.setInstanceType( input.getVmType( ).getName( ) );
        runningInstance.setPlacement( input.getPlacement( ).getPartitionName( ) );
        
        runningInstance.setLaunchTime( input.getLaunchRecord( ).getLaunchTime( ) );
        
        runningInstance.getBlockDevices( ).add( new InstanceBlockDeviceMapping( "/dev/sda1" ) );
        for ( final VmVolumeAttachment attachedVol : input.getTransientVolumeState( ).getAttachments( ) ) {
          runningInstance.getBlockDevices( ).add( new InstanceBlockDeviceMapping( attachedVol.getDevice( ), attachedVol.getVolumeId( ),
                                                                                  attachedVol.getStatus( ),
                                                                                  attachedVol.getAttachTime( ) ) );
        }
        return runningInstance;
      } catch ( final Exception ex ) {
        LOG.error( ex, ex );
        throw Exceptions.toUndeclared( ex );
      }
      
    }
    
  }
  
  public boolean isLinux( ) {
    return this.bootRecord.isLinux( );
  }
  
  public boolean isBlockStorage( ) {
    return this.bootRecord.isBlockStorage( );
  }
  
  @Override
  public void setNaturalId( final String naturalId ) {
    super.setNaturalId( naturalId );
  }
  
  private VmVolumeState getTransientVolumeState( ) {
    if ( this.transientVolumeState == null ) {
      this.transientVolumeState = new VmVolumeState( this );
    }
    return this.transientVolumeState;
  }
  
  @Override
  public String toString( ) {
    final StringBuilder builder2 = new StringBuilder( );
    builder2.append( "VmInstance:" );
    if ( this.networkConfig != null ) builder2.append( "networkConfig=" ).append( this.getNetworkConfig( ) ).append( ":" );
    if ( this.vmId != null ) builder2.append( "vmId=" ).append( this.vmId ).append( ":" );
    if ( this.bootRecord != null ) builder2.append( "bootRecord=" ).append( this.bootRecord ).append( ":" );
    if ( this.usageStats != null ) builder2.append( "usageStats=" ).append( this.usageStats ).append( ":" );
    if ( this.launchRecord != null ) builder2.append( "launchRecord=" ).append( this.launchRecord ).append( ":" );
    if ( this.runtimeState != null ) builder2.append( "runtimeState=" ).append( this.runtimeState ).append( ":" );
    if ( this.transientVolumeState != null ) builder2.append( "transientVolumeState=" ).append( this.transientVolumeState ).append( ":" );
    if ( this.persistentVolumeState != null ) builder2.append( "persistentVolumeState=" ).append( this.persistentVolumeState ).append( ":" );
    if ( this.placement != null ) builder2.append( "placement=" ).append( this.placement ).append( ":" );
    if ( this.privateNetwork != null ) builder2.append( "privateNetwork=" ).append( this.privateNetwork ).append( ":" );
    if ( this.networkGroups != null ) builder2.append( "networkGroups=" ).append( this.networkGroups ).append( ":" );
    if ( this.networkIndex != null ) builder2.append( "networkIndex=" ).append( this.networkIndex );
    return builder2.toString( );
  }
  
  private VmNetworkConfig getNetworkConfig( ) {
    if ( this.networkConfig == null ) {
      this.networkConfig = new VmNetworkConfig( this );
    }
    return this.networkConfig;
  }
  
  private VmVolumeState getPersistentVolumeState( ) {
    if ( this.persistentVolumeState == null ) {
      this.persistentVolumeState = new VmVolumeState( this );
    }
    return this.persistentVolumeState;
  }
  
  private void setNetworkGroups( final Set<NetworkGroup> networkGroups ) {
    this.networkGroups = networkGroups;
  }

  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = super.hashCode( );
    result = prime * result + ( ( this.vmId == null )
      ? 0
      : this.vmId.hashCode( ) );
    return result;
  }

  @Override
  public boolean equals( Object obj ) {
    if ( this == obj ) {
      return true;
    }
    if ( !super.equals( obj ) ) {
      return false;
    }
    if ( getClass( ) != obj.getClass( ) ) {
      return false;
    }
    VmInstance other = ( VmInstance ) obj;
    if ( this.vmId == null ) {
      if ( other.vmId != null ) {
        return false;
      }
    } else if ( !this.vmId.equals( other.vmId ) ) {
      return false;
    }
    return true;
  }
}
