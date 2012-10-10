import boto
import copy
import json
import logging

from json import JSONEncoder
from json import JSONDecoder
from boto.ec2 import EC2Connection
from boto.ec2.ec2object import EC2Object
from boto.regioninfo import RegionInfo
from boto.ec2.blockdevicemapping import BlockDeviceType
from boto.ec2.image import ImageAttribute
from boto.ec2.instance import ConsoleOutput
from boto.ec2.instance import Group
from boto.ec2.securitygroup import GroupOrCIDR
from boto.ec2.securitygroup import IPPermissions
from boto.ec2.volume import AttachmentSet
from .response import ClcError
from .response import Response
from esapi.codecs.html_entity import HTMLEntityCodec

class BotoJsonEncoder(JSONEncoder):
    # use this codec directly vs using factory which messes with logging config
    codec = HTMLEntityCodec()
    IMMUNE_HTML = ',.-_ '
    IMMUNE_HTMLATTR = ',.-_'
    
    def __sanitize_and_copy__(self, dict):
        try:
            ret = copy.copy(dict)
            for key in ret.keys():
                if isinstance(ret[key], basestring):
                    ret[key] = self.codec.encode(self.IMMUNE_HTML, ret[key])
            return ret
        except Exception, e:
            logging.error(e)

    def default(self, obj):
        if issubclass(obj.__class__, EC2Object):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = obj.__class__.__name__
            return (values)
        elif isinstance(obj, RegionInfo):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'RegionInfo'
            return (values)
        elif isinstance(obj, ClcError):
            return obj.__dict__
        elif isinstance(obj, Response):
            return obj.__dict__
        elif isinstance(obj, EC2Connection):
            return []
        elif isinstance(obj, Group):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'Group'
            return (values)
        elif isinstance(obj, ConsoleOutput):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'ConsoleOutput'
            return (values)
        elif isinstance(obj, ImageAttribute):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'ImageAttribute'
            return (values)
        elif isinstance(obj, AttachmentSet):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'AttachmentSet'
            return (values)
        elif isinstance(obj, IPPermissions):
            values = self.__sanitize_and_copy__(obj.__dict__)
            # this is because I found a "parent" property set to self - dak
            values['parent'] = None
            values['__obj_name__'] = 'IPPermissions'
            return (values)
        elif isinstance(obj, GroupOrCIDR):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['__obj_name__'] = 'GroupOrCIDR'
            return (values)
        elif isinstance(obj, BlockDeviceType):
            values = self.__sanitize_and_copy__(obj.__dict__)
            values['connection'] = None
            values['__obj_name__'] = 'BlockDeviceType'
            return (values)
        return super(BotoJsonEncoder, self).default(obj)

class BotoJsonDecoder(JSONDecoder):
    # if we need to map classes to specific boto objects, we'd do that here
    # it seems like we can get away with generic objects for now.
    # regioninfo isn't used, so we just show this for an example
    def default(self, obj):
        if obj['__obj_name__'] == 'RegionInfo':
            ret = RegionInfo()
